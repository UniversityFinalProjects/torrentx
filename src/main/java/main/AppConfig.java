package main;

public class AppConfig {
    private String peerId = "-AZ5750-TpkXttZLfpSH";

    private static AppConfig instance = new AppConfig();

    public static AppConfig getInstance() {
        return AppConfig.instance;
    }

    private AppConfig() {
    }


    public String getPeerId() {
        return peerId;
    }
}