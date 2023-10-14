package cetus.openai.models;

import java.util.List;

public class GPTResponse {
    public String id;
    public String object;
    public long created;
    public String model;
    public List<GPTMessage> messages;

}
