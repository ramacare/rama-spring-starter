package org.rama.ftp;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FtpService {
    private final FtpConnectionManager ftpConnectionManager;
    private final FtpProperties ftpProperties;

    public FtpService(FtpConnectionManager ftpConnectionManager, FtpProperties ftpProperties) {
        this.ftpConnectionManager = ftpConnectionManager;
        this.ftpProperties = ftpProperties;
    }

    public <T> T withClient(String server, FtpConnection.IOFunction<FTPClient, T> fn) {
        return ftpConnectionManager.get(server).withClient(fn);
    }

    public List<String> list(String server, String directory) {
        return ftpConnectionManager.get(server).withClient(client -> {
            FTPFile[] files = client.listFiles(directory);
            List<String> names = new ArrayList<>();
            if (files != null) for (FTPFile f : files) names.add(f.getName());
            return names;
        });
    }

    public boolean exists(String server, String path) {
        return ftpConnectionManager.get(server).withClient(client -> {
            FTPFile[] files = client.listFiles(path);
            return files != null && Arrays.stream(files).anyMatch(FTPFile::isFile);
        });
    }

    public void upload(String server, String remoteDir, String filename, InputStream data, boolean createDirs) {
        ftpConnectionManager.get(server).withClient(client -> {
            if (createDirs) ensureDirectories(client, remoteDir);
            if (!client.changeWorkingDirectory(remoteDir))
                throw new IOException("Cannot change to directory: " + remoteDir);
            try (InputStream in = data) {
                if (!client.storeFile(filename, in))
                    throw new IOException("Upload failed: " + filename + " reply=" + client.getReplyString());
            }
            return null;
        });
    }

    public byte[] download(String server, String remotePath) {
        return ftpConnectionManager.get(server).withClient(client -> {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (!client.retrieveFile(remotePath, out))
                    throw new IOException("Download failed: " + remotePath + " reply=" + client.getReplyString());
                return out.toByteArray();
            }
        });
    }

    public void delete(String server, String remotePath) {
        ftpConnectionManager.get(server).withClient(client -> {
            if (!client.deleteFile(remotePath))
                throw new IOException("Delete failed: " + remotePath + " reply=" + client.getReplyString());
            return null;
        });
    }

    public void writeText(String server, String remoteDir, String filename, String content, boolean createDirs, Charset charset) {
        remoteDir = (remoteDir == null || remoteDir.isEmpty()) ? ftpProperties.getServers().get(server).getOutboundFolder() : remoteDir;
        upload(server, remoteDir, filename, new ByteArrayInputStream(content.getBytes(charset)), createDirs);
    }

    public void writeText(String server, String remoteDir, String filename, String content, boolean createDirs) {
        Charset charset = Charset.forName(ftpProperties.getServers().get(server).getEncoding());
        writeText(server, remoteDir, filename, content, createDirs, charset);
    }

    public String readText(String server, String remotePath, Charset charset) {
        return new String(download(server, remotePath), charset);
    }

    public String readText(String server, String remotePath) {
        return readText(server, remotePath, StandardCharsets.UTF_8);
    }

    public void makeDirectories(String server, String remoteDir) {
        ftpConnectionManager.get(server).withClient(client -> { ensureDirectories(client, remoteDir); return null; });
    }

    private void ensureDirectories(FTPClient client, String dir) throws IOException {
        String normalized = dir.replace("\\", "/");
        String[] parts = normalized.split("/");
        StringBuilder path = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            path.append("/").append(part);
            String p = path.toString();
            if (!client.changeWorkingDirectory(p)) {
                if (!client.makeDirectory(p))
                    throw new IOException("Failed to create directory: " + p + " reply=" + client.getReplyString());
            }
        }
    }
}
