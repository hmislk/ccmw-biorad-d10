package com.carecode.BioradD10;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.*;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.*;

public class BioradD10 {

    private static final Logger logger = Logger.getLogger(BioradD10.class.getName());

    static {
        // Configure logger to show all levels
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
            limsServerBaseUrl = limsSettings.getString("limsServerBaseUrl"); // Renamed for LIMS Server URL
            username = limsSettings.getString("username");
            password = limsSettings.getString("password");
            departmentId = analyzerDetails.getString("departmentId");
            analyzerName = analyzerDetails.getString("analyzerName");
            analyzerId = analyzerDetails.getString("analyzerId");
            departmentAnalyzerId = analyzerDetails.getString("departmentAnalyzerId");

            logger.info("Configuration loaded successfully");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred while loading configuration", e);
        }
    }

    public static String generateUrlForDate(LocalDate date) {
        logger.info("Generating URL for date: " + date);

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy");
            String dateStr = date.format(formatter);

            String url = analyzerBaseURL + "?page=result&test=HBA1C&StartDate=" + encodeDate(dateStr) + "&EndDate=" + encodeDate(dateStr);
            logger.info("Generated URL: " + url);
            return url;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred while generating URL", e);
            return null;
        }
    }

    private static String encodeDate(String date) {
        return date.replace("/", "%2F");
    }

    public static String fetchHtmlContent(String urlString) {
        logger.info("Fetching HTML content from URL: " + urlString);

        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            logger.fine("Sending GET request to URL: " + urlString);
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
                logger.info("Fetched HTML content successfully");
                return htmlContent.toString();
            } else {
                logger.severe("Failed to fetch HTML content. HTTP Response Code: " + responseCode);
                return null;
            }
        } catch (ConnectException e) {
            logger.log(Level.SEVERE, "Connection timed out while fetching HTML content from URL: " + urlString, e);
            return null; // Return null to indicate the connection timed out
        } catch (IOException e) {
            logger.log(Level.SEVERE, "IOException occurred while fetching HTML content from URL: " + urlString, e);
            return null; // Return null to indicate an IO error
        }
    }

    public static List<Map.Entry<String, String>> extractSampleData(String htmlContent) {
        logger.info("Extracting Sample ID and HbA1c percentage from HTML content");

        if (htmlContent == null) {
            logger.info("No response from analyzer");
            return null;
        }
        if (htmlContent.isEmpty()) {
            logger.info("EMpty response");
            return null;
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

            logger.info("Sample data extraction successful");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred while extracting sample data", e);
        }

        return sampleData;
    }

    public static void sendObservationsToLims(List<Map.Entry<String, String>> observations, Date date) {
        logger.info("Sending observations to LIMS");

        // Determine the file name for the day's sample IDs
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        String fileName = "processed_samples_" + dateFormat.format(date) + ".txt";
        Set<String> processedSamples = new HashSet<>();

        // Load the processed samples from the file if it exists
        try {
            Path path = Paths.get(fileName);
            if (Files.exists(path)) {
                System.out.println("Loading processed samples from file: " + fileName);
                processedSamples.addAll(Files.readAllLines(path));
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error reading processed samples file: " + fileName, e);
        }

        // Prepare to write to the file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName, true))) {
            for (Map.Entry<String, String> entry : observations) {
                String sampleId = entry.getKey();
                String observationValue = entry.getValue();

                // Check if the sample ID has already been processed
                if (!processedSamples.contains(sampleId)) {
                    System.out.println("Processing sample ID: " + sampleId + " with HbA1c value: " + observationValue);

                    // Create a custom JSON object to represent the observation
                    JSONObject observationJson = new JSONObject();
                    observationJson.put("sampleId", sampleId);
                    observationJson.put("observationValue", observationValue);
                    observationJson.put("analyzerId", analyzerId);
                    observationJson.put("departmentAnalyzerId", departmentAnalyzerId);
                    observationJson.put("analyzerName", analyzerName);
                    observationJson.put("departmentId", departmentId);
                    observationJson.put("username", username);
                    observationJson.put("password", password);
                    observationJson.put("observationValue", observationValue);
                    observationJson.put("issuedDate", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(new Date()));

                    // Additional attributes for HbA1c percentage
                    observationJson.put("observationValueCodingSystem", "http://loinc.org");
                    observationJson.put("observationValueCode", "4548-4"); // Code for HbA1c as a percentage
                    observationJson.put("observationUnitCodingSystem", "http://unitsofmeasure.org");
                    observationJson.put("observationUnitCode", "%"); // Unit code for percentage

                    // Log the JSON object
                    System.out.println("Prepared Observation JSON: " + observationJson.toString(4));

                    // Send the JSON object to the LIMS server
                    System.out.println("Sending observation to LIMS server...");
                    sendJsonToLimsServer(observationJson);

                    // Write the sample ID to the file
                    writer.write(sampleId);
                    writer.newLine();

                    // Add the sample ID to the processed set
                    processedSamples.add(sampleId);
                } else {
                    logger.info("Sample ID " + sampleId + " has already been processed for today.");
                }
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error writing to processed samples file: " + fileName, e);
        }
    }

    public static void sendJsonToLimsServer(JSONObject observationJson) {
        logger.info("Preparing to send JSON to LIMS server");

        try {
            // Log the JSON being sent
            logger.fine("Observation JSON: " + observationJson.toString(4));

            // Create the URL and open the connection
            URL url = new URL(limsServerBaseUrl + "/observation");
            logger.fine("LIMS Server URL: " + url.toString());
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // Set connection properties
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");

            // Add Basic Authentication header
            String auth = username + ":" + password;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
            connection.setRequestProperty("Authorization", "Basic " + encodedAuth);
            logger.fine("Authorization Header: Basic " + encodedAuth);

            // Send JSON data
            logger.fine("Sending data...");
            OutputStream os = connection.getOutputStream();
            os.write(observationJson.toString().getBytes());
            os.flush();
            os.close();

            // Get response code
            int responseCode = connection.getResponseCode();
            logger.info("Response Code: " + responseCode);

            // Handle server response
            if (responseCode != HttpURLConnection.HTTP_OK) {
                BufferedReader br = new BufferedReader(new InputStreamReader((connection.getErrorStream())));
                String output;
                logger.severe("Error from Server:");
                while ((output = br.readLine()) != null) {
                    logger.severe(output);
                }
                br.close();
            } else {
                BufferedReader br = new BufferedReader(new InputStreamReader((connection.getInputStream())));
                String output;
                logger.info("Response from Server:");
                while ((output = br.readLine()) != null) {
                    logger.info(output);
                }
                br.close();
            }

            connection.disconnect();

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred while sending JSON to LIMS server", e);
        }
    }

    public static void sendRequests() {
        logger.info("Sending requests for today's and potentially yesterday's results");

        LocalDate today = LocalDate.now();
        String todayUrl = generateUrlForDate(today);
        if (todayUrl != null) {
            String htmlContent = fetchHtmlContent(todayUrl);
            logger.info("HTML Content for today: " + htmlContent);
            if (htmlContent != null) {
                List<Map.Entry<String, String>> todayData = extractSampleData(htmlContent);
                if (!todayData.isEmpty()) {
                    sendObservationsToLims(todayData, new Date());
                }
            }
        }
        if (queryForYesterdayResults) {
            LocalDate yesterday = today.minusDays(1);
            Date yday = Date.from(yesterday.atStartOfDay(ZoneId.systemDefault()).toInstant());
            String yesterdayUrl = generateUrlForDate(yesterday);
            if (yesterdayUrl != null) {
                String htmlContent = fetchHtmlContent(yesterdayUrl);
                logger.info("HTML Content for yesterday: " + htmlContent);
                if (htmlContent != null) {
                    List<Map.Entry<String, String>> yesterdayData = extractSampleData(htmlContent);
                    if (!yesterdayData.isEmpty()) {
                        sendObservationsToLims(yesterdayData, yday);
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        logger.info("Main method started");

        try {
            String configPath = "config.json"; // Directly referencing the config file in the project root
            loadConfig(configPath);

            boolean testing = false;

            if (testing) {
                // TEMPORARY TESTING BLOCK
                logger.info("Starting temporary test for sending a sample observation.");

                // Simulated sample observation data
                String testSampleId = "22311";
                String testHbA1cValue = "4.58";

                // Create a mock observation entry
                Map.Entry<String, String> testEntry = new AbstractMap.SimpleEntry<>(testSampleId, testHbA1cValue);
                List<Map.Entry<String, String>> testObservations = Collections.singletonList(testEntry);

                // Send the test observation
                sendObservationsToLims(testObservations, new Date());

                logger.info("Temporary test completed.");
                System.exit(0);

            }

            // NORMAL OPERATION
            sendRequests(); // Initial request at the start

            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    sendRequests();
                }
            }, queryFrequencyInMinutes * 60 * 1000, queryFrequencyInMinutes * 60 * 1000); // Schedule at specified interval

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred in main method", e);
        }

        logger.info("Main method ended");
    }
}
