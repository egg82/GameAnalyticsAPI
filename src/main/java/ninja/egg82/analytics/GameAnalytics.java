package ninja.egg82.analytics;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import ninja.egg82.analytics.events.GAEvent;
import ninja.egg82.analytics.init.GAInit;
import ninja.egg82.analytics.init.GAInitResult;
import ninja.egg82.analytics.utils.GAHMAC;
import ninja.egg82.analytics.utils.JSONUtil;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GameAnalytics implements Closeable {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final Queue<GAEvent> eventQueue = new ConcurrentLinkedQueue<>();
    private final List<JSONObject> failedEvents = new CopyOnWriteArrayList<>();
    private final int maxQueuePressure;

    private final ForkJoinPool threadPool; // Work-stealing pool. Better efficiency when dealing with long-running tasks like network I/O
    private final String endpoint;
    private final Charset utf8 = Charset.forName("UTF-8");
    private final GAHMAC hmacPool;

    private final String sessionId = UUID.randomUUID().toString();

    public String getSessionId() { return sessionId; }

    private final GAInitResult initResult;

    public GAInitResult getInitResult() { return initResult; }

    private AtomicBoolean closed = new AtomicBoolean(false);

    public GameAnalytics(String gameKey, String secretKey, int maxQueuePressure) throws IOException, NoSuchAlgorithmException, InvalidKeyException, ParseException {
        this(gameKey, secretKey, maxQueuePressure, Math.min(Math.max(Runtime.getRuntime().availableProcessors(), 2), 4)); // Processor count- capped at min 2, max 4
    }

    public GameAnalytics(String gameKey, String secretKey, int maxQueuePressure, int threads) throws IOException, NoSuchAlgorithmException, InvalidKeyException, ParseException {
        if (gameKey == null) {
            throw new IllegalArgumentException("gameKey cannot be null.");
        }
        if (secretKey == null) {
            throw new IllegalArgumentException("secretKey cannot be null.");
        }
        if (maxQueuePressure <= 0) {
            throw new IllegalArgumentException("maxQueuePressure cannot be <= 0");
        }
        if (threads <= 1) {
            throw new IllegalArgumentException("threads cannot be <= 1");
        }

        this.maxQueuePressure = maxQueuePressure;
        this.hmacPool = new GAHMAC(secretKey.getBytes(utf8));

        this.threadPool = new ForkJoinPool(threads);
        this.endpoint = "https://api.gameanalytics.com/v2/" + gameKey;

        GAInitResult result;
        HttpURLConnection conn = getConnection(endpoint + "/init");
        write(GAInit.builder().build().getObject(), conn);
        JSONObject obj = (JSONObject) getResults(conn);
        if (obj == null) {
            initResult = new GAInitResult(false, -1, new String[0]);
            return;
        }
        JSONArray flagsArray = (JSONArray) obj.get("flags");
        String[] flags = new String[flagsArray.size()];
        for (int i = 0; i < flagsArray.size(); i++) {
            flags[i] = (String) flagsArray.get(i);
        }
        result = new GAInitResult(
                (Boolean) obj.get("enabled"),
                (int) (Math.floorDiv(System.currentTimeMillis(), 1000L) - ((Number) obj.get("server_ts")).intValue()),
                flags
        );
        initResult = result;
    }

    public void close() {
        if (closed.getAndSet(true)) {
            return;
        }

        if (initResult.getEnabled()) {
            threadPool.execute(this::flushQueue);
        }
        threadPool.shutdown();
    }

    public void queueEvent(GAEvent event) throws IOException {
        if (event == null) {
            throw new IllegalArgumentException("event cannot be null.");
        }

        if (closed.get()) {
            throw new IOException("Connection has been closed.");
        }
        if (!initResult.getEnabled()) {
            throw new IOException("Submitting events has been disabled.");
        }

        eventQueue.add(event);
        if (eventQueue.size() > maxQueuePressure) {
            threadPool.execute(this::flushQueue);
        }
    }

    public JSONObject[] getFailedEvents() { return failedEvents.toArray(new JSONObject[0]); }

    private void flushQueue() {
        List<GAEvent> events = new ArrayList<>();
        long size = new JSONArray().toJSONString().getBytes(utf8).length;

        // Pull events from pool until size limit reached
        do {
            GAEvent first = eventQueue.poll();
            if (first == null) {
                break;
            }

            events.add(first);
            size += first.getObject().toJSONString().getBytes(utf8).length;
        } while (size < 1000000L);

        // Ensure we didn't go over our size limit
        while (size > 1000000L) {
            GAEvent first = events.remove(0);
            if (first == null) {
                break;
            }

            eventQueue.add(first);
            size -= first.getObject().toJSONString().getBytes(utf8).length;
        }

        if (events.isEmpty()) {
            return;
        }

        JSONArray array = new JSONArray();
        array.addAll(events);

        Object obj = null;
        try {
            HttpURLConnection conn = getConnection(endpoint + "/events");
            write(array, conn);
            obj = getResults(conn);
        } catch (IOException ex) {
            if (ex.getCause() instanceof IOException) {
                String error = ex.getCause().getMessage();
                if (error.startsWith("[") || error.startsWith("{")) {
                    try {
                        obj = JSONUtil.parseGeneric(error);
                    } catch (ParseException ex2) {
                        logger.error(ex2.getMessage(), ex2);
                    }
                }
            } else {
                logger.error(ex.getMessage(), ex);
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException | ParseException ex) {
            logger.error(ex.getMessage(), ex);
        }

        if (obj instanceof JSONArray) {
            failedEvents.addAll((JSONArray) obj);
        }

        if (closed.get()) {
            if (!eventQueue.isEmpty()) {
                flushQueue();
            }
        } else {
            if (eventQueue.size() > maxQueuePressure) {
                threadPool.execute(this::flushQueue);
            }
        }
    }

    private void write(JSONArray array, HttpURLConnection conn) throws IOException, NoSuchAlgorithmException, InvalidKeyException {
        byte[] postData = array.toJSONString().getBytes(utf8);
        byte[] hmac = hmacPool.hmac256(postData);

        conn.setRequestProperty("Authorization", Base64.getEncoder().encodeToString(hmac));
        conn.setRequestProperty("Content-Length", String.valueOf(postData.length));

        try (OutputStream out = conn.getOutputStream()) {
            out.write(postData);
            out.flush();
        }
    }

    private void write(JSONObject obj, HttpURLConnection conn) throws IOException, NoSuchAlgorithmException, InvalidKeyException {
        byte[] postData = obj.toJSONString().getBytes(utf8);
        byte[] hmac = hmacPool.hmac256(postData);

        conn.setRequestProperty("Authorization", Base64.getEncoder().encodeToString(hmac));
        conn.setRequestProperty("Content-Length", String.valueOf(postData.length));

        try (OutputStream out = conn.getOutputStream()) {
            out.write(postData);
            out.flush();
        }
    }

    private Object getResults(HttpURLConnection conn) throws IOException, ParseException {
        int code = conn.getResponseCode();

        if (code == 401) {
            throw new IOException("Received 401 (Unauthorized) from GameAnalytics. Please ensure the secret key is correct.");
        } else if (code == 413) {
            throw new IOException("Received 413 (Request Entity Too Large) from GameAnalytics. This should never happen.");
        }

        try (InputStream stream = (code == 200) ? conn.getInputStream() : conn.getErrorStream(); InputStreamReader reader = new InputStreamReader(stream); BufferedReader in = new BufferedReader(reader)) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                builder.append(line).append(System.lineSeparator());
            }

            String error = builder.toString();

            if (code == 400) {
                throw new IOException("Received 400 (Bad Request) from GameAnalytics.", new IOException(error));
            }

            if (error.startsWith("[") || error.startsWith("{")) {
                return JSONUtil.parseGeneric(error);
            }
            return null;
        }
    }

    private HttpURLConnection getConnection(String url) throws IOException {
        HttpURLConnection retVal = (HttpURLConnection) new URL(url).openConnection();

        retVal.setDoOutput(true);
        retVal.setDoInput(true);

        retVal.setRequestProperty("Accept", "application/json");
        retVal.setRequestProperty("Connection", "close");
        retVal.setRequestProperty("Content-Type", "application/json");
        retVal.setRequestProperty("User-Agent", "egg82/GameAnalyticsAPI");

        retVal.setRequestMethod("POST");

        return retVal;
    }
}
