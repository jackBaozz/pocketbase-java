package io.github.jackbaozz.pocketbase.server.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SmtpMailerTest {
  @ParameterizedTest
  @NullAndEmptySource
  void emptyHtmlProducesOnlyPlainText(String html) {
    String raw = message(html, "Only plain text: 中文 <b>not markup</b>").raw();
    int separator = raw.indexOf("\r\n\r\n");
    String headers = raw.substring(0, separator);

    assertTrue(headers.contains("MIME-Version: 1.0\r\n"));
    assertTrue(headers.contains("Content-Type: text/plain; charset=UTF-8\r\n"));
    assertTrue(headers.endsWith("Content-Transfer-Encoding: 8bit"));
    assertFalse(raw.contains("multipart/alternative"));
    assertFalse(raw.contains("Content-Type: text/html"));
    assertFalse(raw.contains("--pb-java-test-boundary"));
    assertEquals("Only plain text: 中文 <b>not markup</b>\r\n", raw.substring(separator + 4));
  }

  @ParameterizedTest
  @ValueSource(strings = {"<p>HTML body</p>", " "})
  void nonEmptyHtmlRetainsBothAlternatives(String html) {
    String raw = message(html, "Plain alternative").raw();

    assertTrue(raw.contains("Content-Type: multipart/alternative;"));
    assertTrue(raw.contains("Content-Type: text/plain; charset=UTF-8\r\n"));
    assertTrue(raw.contains("Content-Type: text/html; charset=UTF-8\r\n"));
    assertTrue(raw.contains("\r\n\r\nPlain alternative\r\n"));
    assertTrue(raw.contains("\r\n\r\n" + html + "\r\n"));
    assertTrue(raw.endsWith("--pb-java-test-boundary--\r\n"));
  }

  @Test
  void emptyMessageStillHasAPlainTextBody() {
    String raw = message("", null).raw();
    assertTrue(raw.contains("Content-Type: text/plain; charset=UTF-8\r\n"));
    assertTrue(raw.endsWith("\r\n\r\n\r\n"));
    assertFalse(raw.contains("Content-Type: text/html"));
  }

  @Test
  void sendsTextOnlyMessageWithoutAnEmptyHtmlPart() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      server.setSoTimeout(10_000);
      Future<String> received = executor.submit(() -> receiveMessage(server));
      SmtpMailer.send(
          new SmtpMailer.Settings(server.getInetAddress().getHostAddress(), server.getLocalPort(), "", "", "PLAIN",
              false, "localhost"),
          message("", "Text-only SMTP body"));

      String raw = received.get(10, TimeUnit.SECONDS);
      assertTrue(raw.contains("Content-Type: text/plain; charset=UTF-8\r\n"));
      assertTrue(raw.contains("\r\n\r\nText-only SMTP body\r\n"));
      assertFalse(raw.contains("multipart/alternative"));
      assertFalse(raw.contains("Content-Type: text/html"));
    } finally {
      executor.shutdownNow();
    }
  }

  private static SmtpMailer.Message message(String html, String text) {
    return new SmtpMailer.Message("Test sender", "sender@example.test", "recipient@example.test", "Test subject", html,
        text);
  }

  private static String receiveMessage(ServerSocket server) throws Exception {
    try (Socket socket = server.accept();
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer =
            new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
      socket.setSoTimeout(10_000);
      reply(writer, "220 test-smtp");
      String captured = "";
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("EHLO ") || line.startsWith("MAIL FROM:") || line.startsWith("RCPT TO:")) {
          reply(writer, "250 OK");
        } else if (line.equals("DATA")) {
          reply(writer, "354 Send message");
          StringBuilder body = new StringBuilder();
          while ((line = reader.readLine()) != null && !line.equals(".")) {
            body.append(line).append("\r\n");
          }
          captured = body.toString();
          reply(writer, "250 Accepted");
        } else if (line.equals("QUIT")) {
          reply(writer, "221 Bye");
          return captured;
        } else {
          throw new AssertionError("Unexpected SMTP command");
        }
      }
      throw new AssertionError("SMTP session ended before QUIT");
    }
  }

  private static void reply(BufferedWriter writer, String line) throws Exception {
    writer.write(line);
    writer.write("\r\n");
    writer.flush();
  }
}
