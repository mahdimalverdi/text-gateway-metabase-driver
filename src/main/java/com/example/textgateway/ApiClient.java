package com.example.textgateway;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class ApiClient {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static boolean ping(String baseUrl) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + "/ping").openConnection();
            return c.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    public static Map<String,Object> runQuery(String baseUrl, String query) {
        try {
            URL url = new URL(baseUrl + "/run");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.getOutputStream().write(("{\"text\":\"" + query + "\"}").getBytes());
            return mapper.readValue(conn.getInputStream(), Map.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<Map<String,Object>> rows(Map<String,Object> result) {
        Object rows = result.get("rows");
        if (rows instanceof List) return (List<Map<String,Object>>) rows;
        return List.of();
    }

    public static List<String> columns(Map<String,Object> result) {
        List<Map<String,Object>> rows = rows(result);
        if (rows.isEmpty()) return List.of();
        return new ArrayList<>(rows.get(0).keySet());
    }
}
