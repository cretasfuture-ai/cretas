package com.cretas.aims.service.tenantbackup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 调用服务器上的 age 可执行文件 (与整库灾备脚本同一个: /usr/local/bin/age, v1.2.1)。
 * 明文从 stdin 流进去, 加密件由 age 直接写到目标文件 —— 明文不落盘。
 */
@Component
public class AgeBackupEncryptor implements BackupEncryptor {

    private final String ageBin;

    public AgeBackupEncryptor(@Value("${cretas.tenant-backup.age-bin:/usr/local/bin/age}") String ageBin) {
        this.ageBin = ageBin;
    }

    @Override
    public void encrypt(String recipient, Writer writer, Path target) throws IOException {
        if (!BackupEncryptor.validRecipient(recipient)) throw new IOException("age 公钥格式不对");
        if (!Files.isExecutable(Path.of(ageBin))) throw new IOException("找不到 age: " + ageBin + " (拒绝上传明文)");
        Path err = Files.createTempFile("tenant-backup-age-", ".err");
        Process p = new ProcessBuilder(List.of(ageBin, "-r", recipient, "-o", target.toString()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(err.toFile())
                .start();
        try {
            IOException writeFailure = null;
            try (OutputStream stdin = p.getOutputStream()) {
                writer.writeTo(stdin);
            } catch (IOException e) {
                writeFailure = e;
            }
            boolean done = p.waitFor(30, TimeUnit.MINUTES);
            if (!done) {
                p.destroyForcibly();
                throw new IOException("age 30 分钟未结束, 已终止");
            }
            String stderr = Files.readString(err, StandardCharsets.UTF_8).trim();
            if (p.exitValue() != 0) {
                throw new IOException("age 退出码 " + p.exitValue() + (stderr.isEmpty() ? "" : ": " + stderr));
            }
            if (writeFailure != null) throw writeFailure;
            if (!hasAgeHeader(target)) throw new IOException("加密件文件头不是 " + AGE_HEADER + ", 不上传");
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("加密被中断", e);
        } finally {
            Files.deleteIfExists(err);
        }
    }

    static boolean hasAgeHeader(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(AGE_HEADER.length());
            return AGE_HEADER.equals(new String(head, StandardCharsets.US_ASCII));
        }
    }
}
