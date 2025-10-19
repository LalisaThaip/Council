package impl;

import java.io.*;
import java.util.*;

public class NetworkConfig {
    public static Map<String, String> loadConfig(String fileName) {
        Map<String, String> config = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length == 3) {
                    config.put(parts[0], parts[1] + ":" + parts[2]);
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading config: " + e.getMessage());
        }
        return config;
    }
}