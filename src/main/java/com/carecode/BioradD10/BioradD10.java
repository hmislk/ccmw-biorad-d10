package com.carecode.BioradD10;

import com.google.gson.Gson;
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
import org.carecode.lims.libraries.AnalyzerDetails;
import org.carecode.lims.libraries.DataBundle;
import org.carecode.lims.libraries.LimsSettings;
import org.carecode.lims.libraries.MiddlewareSettings;
import org.carecode.lims.libraries.ResultsRecord;
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
    private static final Gson gson = new Gson();

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
    private static String chromatogramTestCode;
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
            if (middlewareSettings.has("chromatogramTestCode")) {
                chromatogramTestCode = middlewareSettings.getString("chromatogramTestCode");
                logger.info("Chromatogram test code: " + chromatogramTestCode);
            }
            if (middlewareSettings.has("disableSslVerification")) {
                disableSslVerification = middlewareSettings.getBoolean("disableSslVerification");
                if (disableSslVerification) {
                    logger.info("SSL verification disabled");
                }
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
    // Core: build DataBundle and send to /test_results in a single request
    // -------------------------------------------------------------------------

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

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(processedFileName, true))) {
            for (Map.Entry<String, String> entry : observations) {
                String sampleId = entry.getKey();
                String hba1cValue = entry.getValue();

                if (processedSamples.contains(sampleId)) {
                    logger.info("Sample " + sampleId + " already processed, skipping.");
                    continue;
                }

                // Build DataBundle with all results for this sample
                DataBundle dataBundle = buildDataBundle(sampleId, hba1cValue, checkboxKeys, date);

                logger.info("Sending DataBundle for sample " + sampleId
                        + " with " + dataBundle.getResultsRecords().size() + " result(s)");

                boolean sent = sendDataBundleToLims(dataBundle);

                if (sent) {
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

    private static DataBundle buildDataBundle(String sampleId, String hba1cValue,
                                              Map<String, String> checkboxKeys, Date date) {
        DataBundle db = new DataBundle();

        // Middleware settings
        MiddlewareSettings ms = new MiddlewareSettings();
        AnalyzerDetails ad = new AnalyzerDetails();
        ad.setAnalyzerName(analyzerName);
        ad.setAnalyzerId(analyzerId);
        ad.setDepartmentAnalyzerId(departmentAnalyzerId);
        ad.setDepartmentId(departmentId);
        ms.setAnalyzerDetails(ad);

        LimsSettings ls = new LimsSettings();
        ls.setUsername(username);
        ls.setPassword(password);
        ls.setLimsServerBaseUrl(limsServerBaseUrl);
        ms.setLimsSettings(ls);
        db.setMiddlewareSettings(ms);

        // HbA1c numeric result
        String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(new Date());
        ResultsRecord hba1cResult = new ResultsRecord(
                "4548-4",       // testCode (LOINC code for HbA1c)
                hba1cValue,     // resultValueString
                "%",            // resultUnits
                now,            // resultDateTime
                analyzerName,   // instrumentName
                sampleId        // sampleId
        );
        db.addResultsRecord(hba1cResult);

        // Chromatogram image (if configured and available)
        if (chromatogramTestCode != null && !chromatogramTestCode.isEmpty() && checkboxKeys != null) {
            String checkboxKey = checkboxKeys.get(sampleId);
            if (checkboxKey != null) {
                byte[] pngBytes = getChromatogramPng(sampleId, checkboxKey, date);
                if (pngBytes != null && pngBytes.length > 0) {
                    String base64 = Base64.getEncoder().encodeToString(pngBytes);
                    String imageValue = "^Image^PNG^Base64^" + base64;
                    ResultsRecord chromatogramResult = new ResultsRecord(
                            chromatogramTestCode,   // testCode (e.g. "D10-CHROMATOGRAM")
                            imageValue,             // resultValueString
                            "",                     // resultUnits
                            now,                    // resultDateTime
                            analyzerName,           // instrumentName
                            sampleId                // sampleId
                    );
                    db.addResultsRecord(chromatogramResult);
                    logger.info("Chromatogram included in DataBundle for sample " + sampleId
                            + " (" + pngBytes.length + " bytes)");
                }
            }
        }

        return db;
    }

    /**
     * Gets the chromatogram PNG bytes — from disk cache or by downloading from analyzer.
     */
    private static byte[] getChromatogramPng(String sampleId, String checkboxKey, Date date) {
        if (chromatogramDirectory == null || chromatogramDirectory.isEmpty()) return null;

        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
        File outDir = new File(chromatogramDirectory);
        outDir.mkdirs();
        File outFile = new File(outDir, "chromatogram_" + sampleId + "_" + dateFmt.format(date) + ".png");

        // Return from disk cache if available
        if (outFile.exists()) {
            logger.info("Reading cached chromatogram from disk: " + outFile);
            try {
                return Files.readAllBytes(outFile.toPath());
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to read cached chromatogram: " + outFile, e);
                return null;
            }
        }

        // Download PDF from analyzer and extract image
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
    // LIMS communication
    // -------------------------------------------------------------------------

    public static boolean sendDataBundleToLims(DataBundle dataBundle) {
        logger.info("Sending DataBundle to LIMS /test_results");

        try {
            URL url = new URL(limsServerBaseUrl + "/test_results");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            if (disableSslVerification && connection instanceof HttpsURLConnection) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) connection;
                TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String t) {}
                    public void checkServerTrusted(X509Certificate[] certs, String t) {}
                }};
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAll, new java.security.SecureRandom());
                httpsConn.setSSLSocketFactory(sc.getSocketFactory());
                httpsConn.setHostnameVerifier((hostname, session) -> true);
            }

            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");

            String jsonPayload = gson.toJson(dataBundle);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(jsonPayload.getBytes("UTF-8"));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            logger.info("Response Code: " + responseCode);

            if (responseCode >= 200 && responseCode < 300) {
                BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                br.close();
                logger.info("Response from Server: " + response);
                return true;
            } else {
                InputStream errStream = connection.getErrorStream();
                if (errStream != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(errStream));
                    String line;
                    logger.severe("Error from Server (HTTP " + responseCode + "):");
                    while ((line = br.readLine()) != null) {
                        logger.severe(line);
                    }
                    br.close();
                }
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception sending DataBundle to LIMS", e);
            return false;
        }
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
}
