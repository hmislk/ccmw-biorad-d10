package com.carecode.BioradD10;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.json.JSONObject;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.*;
import javax.net.ssl.*;

public class BioradD10 {

    private static final Logger logger = Logger.getLogger(BioradD10.class.getName());

    static {
        LogManager.getLogManager().reset();
        logger.setLevel(Level.ALL);
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(Level.ALL);
        logger.addHandler(ch);
    }

    private static String analyzerBaseURL;
    private static int queryFrequencyInMinutes;
    private static boolean queryForYesterdayResults;
    private static String limsServerBaseUrl;
    private static String username;
    private static String password;
    static String departmentId;
    static String analyzerId;
    static String departmentAnalyzerId;
    static String analyzerName;
    private static String chromatogramDirectory;
    private static String chromatogramObservationCodeSystem;
    private static String chromatogramObservationCode;
    private static boolean disableSslVerification;

    public BioradD10() {
    }

    public static void loadConfig(String configFilePath) {
        logger.info("Loading configuration from file: " + configFilePath);
        try {
            String content = new String(Files.readAllBytes(Paths.get(configFilePath)));
            JSONObject config = new JSONObject(content);
            JSONObject middlewareSettings = config.getJSONObject("middlewareSettings");
            JSONObject analyzerDetails = middlewareSettings.getJSONObject("analyzerDetails");
            JSONObject communicationSettings = middlewareSettings.getJSONObject("communication");
            JSONObject limsSettings = middlewareSettings.getJSONObject("limsSettings");

            analyzerBaseURL = analyzerDetails.getString("analyzerBaseURL");
            queryFrequencyInMinutes = communicationSettings.getInt("queryFrequencyInMinutes");
            queryForYesterdayResults = communicationSettings.getBoolean("queryForYesterdayResults");
            limsServerBaseUrl = limsSettings.getString("limsServerBaseUrl");
            username = limsSettings.getString("username");
            password = limsSettings.getString("password");
            departmentId = analyzerDetails.getString("departmentId");
            analyzerName = analyzerDetails.getString("analyzerName");
            analyzerId = analyzerDetails.getString("analyzerId");
            departmentAnalyzerId = analyzerDetails.getString("departmentAnalyzerId");

            if (middlewareSettings.has("chromatogramDirectory")) {
                chromatogramDirectory = middlewareSettings.getString("chromatogramDirectory");
                logger.info("Chromatogram directory: " + chromatogramDirectory);
            }
            // Support both key names for backward compatibility
            if (middlewareSettings.has("chromatogramObservationCode")) {
                chromatogramObservationCode = middlewareSettings.getString("chromatogramObservationCode");
            } else if (middlewareSettings.has("chromatogramTestCode")) {
                chromatogramObservationCode = middlewareSettings.getString("chromatogramTestCode");
            }
            if (middlewareSettings.has("chromatogramObservationCodeSystem")) {
                chromatogramObservationCodeSystem = middlewareSettings.getString("chromatogramObservationCodeSystem");
            }
            if (chromatogramObservationCode != null) {
                logger.info("Chromatogram observation: "
                        + chromatogramObservationCodeSystem + " / " + chromatogramObservationCode);
            }

            if (middlewareSettings.has("disableSslVerification")
                    && middlewareSettings.getBoolean("disableSslVerification")) {
                disableSslVerification = true;
                trustAllCertificates();
            }

            logger.info("Configuration loaded successfully");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred while loading configuration", e);
        }
    }

    public static String generateUrlForDate(LocalDate date) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy");
        String dateStr = date.format(formatter);
        String url = analyzerBaseURL + "?page=result&test=HBA1C&StartDate="
                + dateStr.replace("/", "%2F") + "&EndDate=" + dateStr.replace("/", "%2F");
        logger.info("Generated URL: " + url);
        return url;
    }

    public static String fetchHtmlContent(String urlString) {
        logger.info("Fetching HTML content from URL: " + urlString);
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("GET");
            int responseCode = connection.getResponseCode();
            logger.info("Response Code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder htmlContent = new StringBuilder();
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    htmlContent.append(inputLine);
                }
                in.close();
                return htmlContent.toString();
            } else {
                logger.severe("Failed to fetch HTML content. HTTP Response Code: " + responseCode);
                return null;
            }
        } catch (ConnectException e) {
            logger.log(Level.SEVERE, "Connection timed out: " + urlString, e);
            return null;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "IOException: " + urlString, e);
            return null;
        }
    }

    public static List<Map.Entry<String, String>> extractSampleData(String htmlContent) {
        if (htmlContent == null || htmlContent.isEmpty()) {
            logger.info("No response from analyzer");
            return Collections.emptyList();
        }

        List<Map.Entry<String, String>> sampleData = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(htmlContent);
            Elements rows = doc.select("table tr");
            for (Element row : rows) {
                Elements cells = row.select("td");
                if (cells.size() > 5) {
                    String sampleId = cells.get(3).text();
                    String hba1c = cells.get(5).text();
                    sampleData.add(new AbstractMap.SimpleEntry<>(sampleId, hba1c));
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception extracting sample data", e);
        }
        return sampleData;
    }

    public static Map<String, String> extractCheckboxKeys(String htmlContent) {
        Map<String, String> keys = new LinkedHashMap<>();
        if (htmlContent == null || htmlContent.isEmpty()) return keys;
        try {
            Document doc = Jsoup.parse(htmlContent);
            Elements checkboxes = doc.select("input[type=checkbox][name!=_allbox]");
            for (Element cb : checkboxes) {
                String val = cb.attr("value").trim();
                if (!val.isEmpty()) {
                    String sampleId = val.contains(" ") ? val.substring(0, val.indexOf(' ')) : val;
                    keys.put(sampleId, val);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error extracting checkbox keys", e);
        }
        return keys;
    }

    // -------------------------------------------------------------------------
    // LIMS communication
    // -------------------------------------------------------------------------

    private static final String RESULT_LOG_DIR = "biorad_D10_logs/result_log";
    private static final String RESULT_LOG_SEP  = "+-----------------------+------------------+------------+-------------+------------+";
    private static final String RESULT_LOG_HDR  = "| Sent At               | Sample ID        | Test Code  | Result      | Units      |";

    public static void sendObservationsToLims(List<Map.Entry<String, String>> observations,
                                              Map<String, String> checkboxKeys,
                                              Date date) {
        logger.info("Sending observations to LIMS");

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        String processedFileName = "processed_samples_" + dateFormat.format(date) + ".txt";
        Set<String> processedSamples = new HashSet<>();

        try {
            Path path = Paths.get(processedFileName);
            if (Files.exists(path)) {
                processedSamples.addAll(Files.readAllLines(path));
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error reading processed samples file", e);
        }

        String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(new Date());

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(processedFileName, true))) {
            for (Map.Entry<String, String> entry : observations) {
                String sampleId = entry.getKey();
                String hba1cValue = entry.getValue();

                if (processedSamples.contains(sampleId)) {
                    logger.info("Sample " + sampleId + " already processed, skipping.");
                    continue;
                }

                // Build HbA1c observation
                JSONObject obs = buildObservationJson(sampleId, hba1cValue,
                        "http://loinc.org", "4548-4",
                        "http://unitsofmeasure.org", "%", now);

                logger.info("Sending DataBundle to LIMS /observation for sample " + sampleId);
                boolean sent = sendJsonToLimsServer(obs);

                if (sent) {
                    String sentAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                    writeResultLog(sentAt, sampleId, "HbA1c", hba1cValue, "%");

                    // Send chromatogram as a second observation
                    sendChromatogramObservation(sampleId, checkboxKeys, date, now);

                    writer.write(sampleId);
                    writer.newLine();
                    processedSamples.add(sampleId);
                    logger.info("Sample " + sampleId + " processed successfully.");
                } else {
                    logger.warning("Failed to send results for sample " + sampleId);
                }
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error writing processed samples file", e);
        }
    }

    private static void writeResultLog(String sentAt, String sampleId, String testCode,
                                        String result, String units) {
        try {
            Path dir = Paths.get(RESULT_LOG_DIR);
            Files.createDirectories(dir);

            // File name: d10-2026.05.22.txt  (use dots, derived from sentAt date)
            String dateStr = sentAt.substring(0, 10).replace("-", ".");
            Path logFile = dir.resolve("d10-" + dateStr + ".txt");

            boolean isNew = !Files.exists(logFile);
            try (BufferedWriter w = new BufferedWriter(new FileWriter(logFile.toFile(), true))) {
                if (isNew) {
                    w.write("================================================================"); w.newLine();
                    w.write("  RESULT LOG — " + dateStr); w.newLine();
                    w.write("================================================================"); w.newLine();
                    w.write(RESULT_LOG_SEP); w.newLine();
                    w.write(RESULT_LOG_HDR); w.newLine();
                    w.write(RESULT_LOG_SEP); w.newLine();
                }
                String row = String.format("| %-21s | %-16s | %-10s | %-11s | %-10s |",
                        sentAt, sampleId, testCode, result, units);
                w.write(row); w.newLine();
                w.write(RESULT_LOG_SEP); w.newLine();
            }
            logger.info("Result logged: " + logFile);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to write result log", e);
        }
    }

    private static void sendChromatogramObservation(String sampleId, Map<String, String> checkboxKeys,
                                                     Date date, String now) {
        if (chromatogramObservationCode == null || chromatogramObservationCode.isEmpty()) return;
        if (checkboxKeys == null) return;

        String checkboxKey = checkboxKeys.get(sampleId);
        if (checkboxKey == null) return;

        byte[] pngBytes = getChromatogramPng(sampleId, checkboxKey, date);
        if (pngBytes == null || pngBytes.length == 0) return;

        String base64 = Base64.getEncoder().encodeToString(pngBytes);
        String imageValue = "^Image^PNG^Base64^" + base64;

        String codingSystem = (chromatogramObservationCodeSystem != null && !chromatogramObservationCodeSystem.isEmpty())
                ? chromatogramObservationCodeSystem : "D10-IMG";

        JSONObject imgObs = buildObservationJson(sampleId, imageValue,
                codingSystem, chromatogramObservationCode,
                "", "", now);

        logger.info("Sending chromatogram observation for sample " + sampleId
                + " (" + pngBytes.length + " bytes)");
        sendJsonToLimsServer(imgObs);
    }

    private static JSONObject buildObservationJson(String sampleId, String value,
                                                    String valueCodingSystem, String valueCode,
                                                    String unitCodingSystem, String unitCode,
                                                    String issuedDate) {
        JSONObject obs = new JSONObject();
        obs.put("sampleId", sampleId);
        obs.put("observationValue", value);
        obs.put("analyzerId", analyzerId);
        obs.put("departmentAnalyzerId", departmentAnalyzerId);
        obs.put("analyzerName", analyzerName);
        obs.put("departmentId", departmentId);
        obs.put("username", username);
        obs.put("password", password);
        obs.put("issuedDate", issuedDate);
        obs.put("observationValueCodingSystem", valueCodingSystem);
        obs.put("observationValueCode", valueCode);
        obs.put("observationUnitCodingSystem", unitCodingSystem);
        obs.put("observationUnitCode", unitCode);
        return obs;
    }

    public static boolean sendJsonToLimsServer(JSONObject observationJson) {
        logger.info("Preparing to send JSON to LIMS server");
        try {
            URL url = new URL(limsServerBaseUrl + "/observation");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");

            String auth = username + ":" + password;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes("UTF-8"));
            connection.setRequestProperty("Authorization", "Basic " + encodedAuth);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(observationJson.toString().getBytes("UTF-8"));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            logger.info("Response Code: " + responseCode);

            if (responseCode >= 200 && responseCode < 300) {
                BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) response.append(line);
                br.close();
                logger.info("Response from Server: " + response);
                return true;
            } else {
                InputStream errStream = connection.getErrorStream();
                if (errStream != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(errStream));
                    String line;
                    logger.severe("Error from Server (HTTP " + responseCode + "):");
                    while ((line = br.readLine()) != null) logger.severe(line);
                    br.close();
                }
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception sending to LIMS", e);
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Chromatogram extraction
    // -------------------------------------------------------------------------

    private static byte[] getChromatogramPng(String sampleId, String checkboxKey, Date date) {
        if (chromatogramDirectory == null || chromatogramDirectory.isEmpty()) return null;

        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
        File outDir = new File(chromatogramDirectory);
        outDir.mkdirs();
        File outFile = new File(outDir, "chromatogram_" + sampleId + "_" + dateFmt.format(date) + ".png");

        if (outFile.exists()) {
            logger.info("Reading cached chromatogram from disk: " + outFile);
            try {
                return Files.readAllBytes(outFile.toPath());
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to read cached chromatogram: " + outFile, e);
                return null;
            }
        }

        String encodedKey = checkboxKey.replace(" ", "+");
        String pdfUrl = analyzerBaseURL + "?page=pdf&test=HBA1C&nbfile=1&f0=" + encodedKey;
        logger.info("Downloading chromatogram PDF for: " + sampleId);

        byte[] pdfBytes = fetchBytes(pdfUrl);
        if (pdfBytes == null) {
            logger.warning("Could not download PDF for chromatogram: " + sampleId);
            return null;
        }

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDPage page = doc.getPage(0);
            PDResources resources = page.getResources();
            for (COSName name : resources.getXObjectNames()) {
                Object xobj = resources.getXObject(name);
                if (xobj instanceof PDImageXObject) {
                    BufferedImage bImg = ((PDImageXObject) xobj).getImage();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(bImg, "PNG", baos);
                    byte[] pngBytes = baos.toByteArray();
                    Files.write(outFile.toPath(), pngBytes);
                    logger.info("Chromatogram saved: " + outFile.getAbsolutePath());
                    return pngBytes;
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to extract chromatogram for: " + sampleId, e);
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Scheduling
    // -------------------------------------------------------------------------

    public static void sendRequests() {
        logger.info("Sending requests for today's and potentially yesterday's results");

        LocalDate today = LocalDate.now();
        processDate(today, new Date());

        if (queryForYesterdayResults) {
            LocalDate yesterday = today.minusDays(1);
            Date yday = Date.from(yesterday.atStartOfDay(ZoneId.systemDefault()).toInstant());
            processDate(yesterday, yday);
        }
    }

    private static void processDate(LocalDate localDate, Date date) {
        String url = generateUrlForDate(localDate);
        if (url == null) return;

        String htmlContent = fetchHtmlContent(url);
        if (htmlContent == null) return;

        List<Map.Entry<String, String>> sampleData = extractSampleData(htmlContent);
        if (sampleData.isEmpty()) return;

        Map<String, String> checkboxKeys = extractCheckboxKeys(htmlContent);
        sendObservationsToLims(sampleData, checkboxKeys, date);
    }

    public static void main(String[] args) {
        logger.info("Main method started");

        try {
            String configPath = args.length > 0
                    ? args[0]
                    : "D:\\ccmv\\settings\\d10\\config.json";
            loadConfig(configPath);

            sendRequests();

            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    sendRequests();
                }
            }, queryFrequencyInMinutes * 60 * 1000L, queryFrequencyInMinutes * 60 * 1000L);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred in main method", e);
        }

        logger.info("Main method ended");
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static byte[] fetchBytes(String urlString) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                logger.warning("HTTP " + conn.getResponseCode() + " fetching: " + urlString);
                return null;
            }
            try (InputStream is = conn.getInputStream();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                return baos.toByteArray();
            }
        } catch (ConnectException e) {
            logger.warning("Cannot reach analyzer: " + e.getMessage());
            return null;
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error fetching bytes: " + urlString, e);
            return null;
        }
    }

    private static void trustAllCertificates() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
            logger.warning("SSL verification DISABLED — for development use only");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to disable SSL verification", e);
        }
    }
}
