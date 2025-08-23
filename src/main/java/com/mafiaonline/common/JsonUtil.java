package com.mafiaonline.common;

import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonUtil {
    private static final ObjectMapper mapper = new ObjectMapper();

    // JSON → Object
    public static Message fromJson(String json) throws Exception {
        return mapper.readValue(json, Message.class);
    }

    // Object → JSON
    public static String toJson(Message message) throws Exception {
        return mapper.writeValueAsString(message);
    }
}
