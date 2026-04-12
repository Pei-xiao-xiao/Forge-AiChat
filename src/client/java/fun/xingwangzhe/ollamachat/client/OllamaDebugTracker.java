package fun.xingwangzhe.ollamachat.client;

public class OllamaDebugTracker {
    private static String lastMessageSource = "N/A";
    private static String lastRequest = "N/A";
    private static String lastResponse = "N/A";

    public static void setMessageSource(boolean isClientMessage) {
        lastMessageSource = isClientMessage ? "Client" : "Server";
    }

    public static String getMessageSource() {
        return lastMessageSource;
    }

    public static void setLastRequest(String request) {
        lastRequest = request;
    }

    public static String getLastRequest() {
        return lastRequest;
    }

    public static void setLastResponse(String response) {
        lastResponse = response;
    }

    public static String getLastResponse() {
        return lastResponse;
    }
}