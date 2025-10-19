package main.java.impl;

import java.io.*;
import java.util.*;

/**
 * NetworkConfig is a utility class for loading network configuration
 * details from a file. Each line in the configuration file is expected
 * to have the format: nodeName:host:port
 *
 * Example line: M1:localhost:8000
 *
 * The class provides a static method to read the file and return
 * a Map where the key is the node name and the value is host:port.
 */

public class NetworkConfig {
    /**
     * Loads network configuration from a given file.
     *
     * @param fileName the path to the configuration file
     * @return a Map where the key is the node name (e.g., "M1") and the
     *         value is the host:port string (e.g., "localhost:8000")
     */
    public static Map<String, String> loadConfig(String fileName) {
        // Map to store the configuration (node -> host:port)
        Map<String, String> config = new HashMap<>();

        // Try-with-resources ensures the file reader is closed automatically
        try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
            String line;
            // Read the file line by line
            while ((line = reader.readLine()) != null) {
                // Split the line by colon into 3 parts: node, host, port
                String[] parts = line.split(":");

                // Only process lines with exactly 3 parts
                if (parts.length == 3) {
                    // Combine host and port into a single string and store in map
                    config.put(parts[0], parts[1] + ":" + parts[2]);
                }
                // Lines with unexpected format are ignored silently
            }
        } catch (IOException e) {
            // Print an error message if the file cannot be read
            System.err.println("Error reading config: " + e.getMessage());
        }
        // Return the populated configuration map
        return config;
    }
}