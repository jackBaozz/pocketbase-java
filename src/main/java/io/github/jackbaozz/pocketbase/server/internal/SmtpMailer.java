package io.github.jackbaozz.pocketbase.server.internal;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SmtpMailer {
    private SmtpMailer() {
    }

    public static void send(Settings settings, Message message) {
        try (Session session = Session.open(settings)) {
            session.expect(220);
            List<String> ehlo = session.ehlo();
            if (settings.tls() && !settings.directTls() && supports(ehlo, "STARTTLS")) {
                session.command("STARTTLS", 220);
                session.startTls();
                session.ehlo();
            }
            if (!settings.username().isBlank()) {
                session.authenticate(settings);
            }
            session.command("MAIL FROM:<" + cleanAddress(message.fromAddress()) + ">", 250);
            session.command("RCPT TO:<" + cleanAddress(message.to()) + ">", 250, 251);
            session.command("DATA", 354);
            session.writeData(message.raw());
            session.expect(250);
            session.command("QUIT", 221, 250);
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new ApiException(
                    400,
                    "Failed to send the test email. Raw error: \n" + detail,
                    ApiErrors.invalidField("smtp", detail)
            );
        }
    }

    private static boolean supports(List<String> lines, String extension) {
        for (String line : lines) {
            if (line.toUpperCase(Locale.ROOT).contains(extension)) {
                return true;
            }
        }
        return false;
    }

    private static String cleanAddress(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank() || text.contains("\r") || text.contains("\n") || !text.contains("@")) {
            throw new ApiException(400, "Failed to send the test email.", ApiErrors.invalidField("email", "Invalid email address."));
        }
        return text;
    }

    public record Settings(
            String host,
            int port,
            String username,
            String password,
            String authMethod,
            boolean tls,
            String localName
    ) {
        boolean directTls() {
            return tls && port == 465;
        }
    }

    public record Message(
            String fromName,
            String fromAddress,
            String to,
            String subject,
            String html,
            String text
    ) {
        String raw() {
            StringBuilder out = new StringBuilder();
            out.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())).append("\r\n");
            out.append("From: ").append(mailbox(fromName, fromAddress)).append("\r\n");
            out.append("To: ").append(mailbox("", to)).append("\r\n");
            out.append("Subject: ").append(encodedHeader(subject)).append("\r\n");
            out.append("MIME-Version: 1.0\r\n");
            out.append("Content-Type: multipart/alternative; boundary=\"pb-java-test-boundary\"\r\n");
            out.append("\r\n");
            out.append("--pb-java-test-boundary\r\n");
            out.append("Content-Type: text/plain; charset=UTF-8\r\n");
            out.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
            out.append(noCrlf(text)).append("\r\n");
            out.append("--pb-java-test-boundary\r\n");
            out.append("Content-Type: text/html; charset=UTF-8\r\n");
            out.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
            out.append(noCrlf(html)).append("\r\n");
            out.append("--pb-java-test-boundary--\r\n");
            return out.toString();
        }

        private static String mailbox(String name, String address) {
            String cleanAddress = cleanAddress(address);
            String cleanName = noCrlf(name).trim();
            if (cleanName.isBlank()) {
                return "<" + cleanAddress + ">";
            }
            return encodedHeader(cleanName) + " <" + cleanAddress + ">";
        }

        private static String encodedHeader(String value) {
            String text = noCrlf(value).trim();
            if (text.isBlank()) {
                return "";
            }
            return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "?=";
        }

        private static String noCrlf(String value) {
            return value == null ? "" : value.replace("\r", "").replace("\n", " ");
        }
    }

    private static final class Session implements AutoCloseable {
        private final String localName;
        private Socket socket;
        private BufferedReader reader;
        private BufferedWriter writer;

        private Session(Socket socket, String localName) throws IOException {
            this.localName = localName == null || localName.isBlank() ? "" : localName.trim();
            this.socket = socket;
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        static Session open(Settings settings) throws IOException {
            Socket socket = settings.directTls() ? SSLSocketFactory.getDefault().createSocket() : new Socket();
            socket.connect(new InetSocketAddress(settings.host(), settings.port()), 10_000);
            socket.setSoTimeout(15_000);
            if (settings.directTls()) {
                ((javax.net.ssl.SSLSocket) socket).startHandshake();
            }
            return new Session(socket, settings.localName());
        }

        List<String> ehlo() throws IOException {
            String name = !localName.isBlank()
                    ? localName
                    : socket.getLocalAddress() == null
                    ? "localhost"
                    : socket.getLocalAddress().getHostName();
            return command("EHLO " + name, 250);
        }

        void startTls() throws IOException {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            Socket tls = factory.createSocket(
                    socket,
                    socket.getInetAddress().getHostAddress(),
                    socket.getPort(),
                    true
            );
            ((javax.net.ssl.SSLSocket) tls).startHandshake();
            this.socket = tls;
            this.reader = new BufferedReader(new InputStreamReader(tls.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(tls.getOutputStream(), StandardCharsets.UTF_8));
        }

        void authenticate(Settings settings) throws IOException {
            String method = settings.authMethod() == null ? "" : settings.authMethod().trim().toUpperCase(Locale.ROOT);
            if ("LOGIN".equals(method)) {
                command("AUTH LOGIN", 334);
                command(Base64.getEncoder().encodeToString(settings.username().getBytes(StandardCharsets.UTF_8)), 334);
                command(Base64.getEncoder().encodeToString(settings.password().getBytes(StandardCharsets.UTF_8)), 235);
                return;
            }
            String value = "\0" + settings.username() + "\0" + settings.password();
            command("AUTH PLAIN " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)), 235);
        }

        List<String> command(String command, int... expectedCodes) throws IOException {
            writer.write(command);
            writer.write("\r\n");
            writer.flush();
            return expect(expectedCodes);
        }

        void writeData(String raw) throws IOException {
            for (String line : raw.split("\\r?\\n", -1)) {
                if (line.startsWith(".")) {
                    writer.write('.');
                }
                writer.write(line);
                writer.write("\r\n");
            }
            writer.write(".\r\n");
            writer.flush();
        }

        List<String> expect(int... expectedCodes) throws IOException {
            List<String> lines = readReply();
            int code = replyCode(lines);
            for (int expected : expectedCodes) {
                if (code == expected) {
                    return lines;
                }
            }
            throw new IOException("SMTP command failed: " + String.join(" | ", lines));
        }

        private List<String> readReply() throws IOException {
            List<String> lines = new ArrayList<>();
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    throw new IOException("SMTP server closed the connection");
                }
                lines.add(line);
                if (line.length() < 4 || line.charAt(3) != '-') {
                    return lines;
                }
            }
        }

        private int replyCode(List<String> lines) throws IOException {
            if (lines.isEmpty() || lines.get(0).length() < 3) {
                throw new IOException("invalid SMTP response");
            }
            try {
                return Integer.parseInt(lines.get(0).substring(0, 3));
            } catch (NumberFormatException e) {
                throw new IOException("invalid SMTP response: " + lines.get(0), e);
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
