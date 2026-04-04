package com.droidrocks.ondeviceai.data;

/**
 * Represents a model available for download.
 */
public class AvailableModel {
    private String name;
    private String description;
    private String url;
    private String filename;
    private String size;
    private boolean downloaded;

    public AvailableModel(String name, String description, String url, String size) {
        this.name = name;
        this.description = description;
        this.url = url;
        this.size = size;
        this.downloaded = false;

        // Extract filename from URL
        String temp = url;
        int q = temp.indexOf('?');
        if (q >= 0) temp = temp.substring(0, q);
        int s = temp.lastIndexOf('/');
        if (s >= 0) temp = temp.substring(s + 1);
        this.filename = temp;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public boolean isDownloaded() {
        return downloaded;
    }

    public void setDownloaded(boolean downloaded) {
        this.downloaded = downloaded;
    }
}

