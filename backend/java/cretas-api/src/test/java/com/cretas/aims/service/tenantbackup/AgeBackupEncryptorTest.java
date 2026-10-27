package com.cretas.aims.service.tenantbackup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 age 可执行文件: 加密件能用对应私钥解回原文, 且不含明文; 写入方中途失败、age 不存在都不留下「成功」。
 * 需要 {@code CRETAS_AGE_BIN} 指向 age, 同目录有 age-keygen (CI 托管机没有 age, 本机与 47 上跑)。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_AGE_BIN", matches = ".+")
class AgeBackupEncryptorTest {

    @TempDir Path dir;

    @Test
    void roundTripsThroughTheRealAgeBinaryAndTheCiphertextHasNoPlaintext() throws Exception {
        Path age = Path.of(System.getenv("CRETAS_AGE_BIN"));
        Path key = dir.resolve("key.txt");
        String recipient = keygen(age, key);
        byte[] plain = ("manifest.json TBK-SECRET-ROW " + "x".repeat(200_000)).getBytes(StandardCharsets.UTF_8);
        Path enc = dir.resolve("b.zip.age");

        new AgeBackupEncryptor(age.toString()).encrypt(recipient, out -> out.write(plain), enc);

        assertThat(AgeBackupEncryptor.hasAgeHeader(enc)).isTrue();
        assertThat(new String(Files.readAllBytes(enc), StandardCharsets.ISO_8859_1)).doesNotContain("TBK-SECRET-ROW");
        Path dec = dir.resolve("b.zip");
        run(List.of(age.toString(), "-d", "-i", key.toString(), "-o", dec.toString(), enc.toString()));
        assertThat(Files.readAllBytes(dec)).isEqualTo(plain);
    }

    @Test
    void aWriterFailureFailsTheEncryption() throws Exception {
        Path age = Path.of(System.getenv("CRETAS_AGE_BIN"));
        String recipient = keygen(age, dir.resolve("key.txt"));
        Path enc = dir.resolve("c.zip.age");

        assertThatThrownBy(() -> new AgeBackupEncryptor(age.toString()).encrypt(recipient, out -> {
            out.write("partial".getBytes(StandardCharsets.UTF_8));
            throw new IOException("export broke");
        }, enc)).hasMessageContaining("export broke");
    }

    @Test
    void missingBinaryOrBadRecipientIsRefused() {
        Path enc = dir.resolve("d.zip.age");
        assertThatThrownBy(() -> new AgeBackupEncryptor(dir.resolve("no-age").toString())
                .encrypt("age1" + "q".repeat(58), out -> {}, enc)).hasMessageContaining("拒绝上传明文");
        assertThatThrownBy(() -> new AgeBackupEncryptor(System.getenv("CRETAS_AGE_BIN"))
                .encrypt("ssh-rsa AAAA", out -> {}, enc)).hasMessageContaining("公钥格式");
        assertThat(enc).doesNotExist();
    }

    private String keygen(Path age, Path key) throws Exception {
        String exe = age.getFileName().toString().endsWith(".exe") ? "age-keygen.exe" : "age-keygen";
        run(List.of(age.resolveSibling(exe).toString(), "-o", key.toString()));
        return Files.readAllLines(key).stream().filter(l -> l.startsWith("# public key: "))
                .map(l -> l.substring("# public key: ".length()).trim()).findFirst().orElseThrow();
    }

    private static void run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
    }
}
