package com.mafiaonline.common;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Message {
    @JsonProperty("type")
    private MessageType type;

    @JsonProperty("sender")
    private String sender;

    @JsonProperty("content")
    private String content;

    @JsonProperty("timestamp")
    private long timestamp;

    public Message() {}

    public Message(MessageType type, String sender, String content, long timestamp) {
        this.type = type;
        this.sender = sender;
        this.content = content;
        this.timestamp = timestamp;
    }

    // Getter & Setter
    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
