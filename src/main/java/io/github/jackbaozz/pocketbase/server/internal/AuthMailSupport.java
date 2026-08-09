package io.github.jackbaozz.pocketbase.server.internal;

import io.github.jackbaozz.pocketbase.server.model.CollectionSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AuthMailSupport {
  private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
  private static final AtomicInteger MAIL_THREAD_ID = new AtomicInteger();
  private static final ThreadPoolExecutor MAIL_EXECUTOR =
      new ThreadPoolExecutor(
          2,
          8,
          60L,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(256),
          new MailThreadFactory(),
          new ThreadPoolExecutor.AbortPolicy());

  private AuthMailSupport() {
  }

  public static boolean sendAsync(
      CollectionSchema collection,
      Map<String, Object> record,
      Map<String, Object> request,
      Map<String, Object> settings,
      Runnable onFailure) {
    Map<String, Object> smtp = section(settings, "smtp");
    if (!truthy(smtp.get("enabled")) || text(smtp.get("host")).isBlank()) {
      return false;
    }

    SmtpMailer.Settings smtpSettings = smtpSettings(smtp);
    SmtpMailer.Message message = message(collection, record, request, settings);
    try {
      MAIL_EXECUTOR.execute(
          () -> {
            try {
              SmtpMailer.send(smtpSettings, message);
            } catch (RuntimeException e) {
              if (onFailure != null) {
                onFailure.run();
              }
            }
          });
      return true;
    } catch (java.util.concurrent.RejectedExecutionException e) {
      // Let the caller persist the request in the development outbox instead of creating an
      // unbounded number of threads when SMTP is slow or unavailable.
      return false;
    }
  }

  private static final class MailThreadFactory implements ThreadFactory {
    @Override
    public Thread newThread(Runnable task) {
      Thread thread =
          new Thread(task, "pocketbase-java-auth-mail-" + MAIL_THREAD_ID.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }

  static SmtpMailer.Message message(
      CollectionSchema collection,
      Map<String, Object> record,
      Map<String, Object> request,
      Map<String, Object> settings) {
    CollectionSchema.EmailTemplate template = template(collection, text(request.get("type")));
    Map<String, String> placeholders = placeholders(record, request, settings);
    String subject = resolve(template.subject, placeholders);
    String html = resolve(template.body, placeholders);
    Map<String, Object> meta = section(settings, "meta");
    String recipient =
        "emailChange".equals(text(request.get("type")))
            ? text(request.get("newEmail"))
            : text(request.get("email"));
    String senderAddress = text(meta.get("senderAddress"));
    if (senderAddress.isBlank()) {
      senderAddress = "noreply@example.com";
    }
    return new SmtpMailer.Message(
        text(meta.get("senderName")), senderAddress, recipient, subject, html, plainText(html));
  }

  private static CollectionSchema.EmailTemplate template(CollectionSchema collection, String type) {
    CollectionSchema defaults = new CollectionSchema();
    CollectionSchema.EmailTemplate selected =
        switch (type) {
          case "verification" -> collection == null ? null : collection.verificationTemplate;
          case "passwordReset" -> collection == null ? null : collection.resetPasswordTemplate;
          case "emailChange" -> collection == null ? null : collection.confirmEmailChangeTemplate;
          case "otp" ->
            collection == null || collection.otp == null ? null : collection.otp.emailTemplate;
          case "authAlert" ->
            collection == null || collection.authAlert == null
                ? null
                : collection.authAlert.emailTemplate;
          default -> null;
        };
    CollectionSchema.EmailTemplate fallback =
        switch (type) {
          case "verification" -> defaults.verificationTemplate;
          case "passwordReset" -> defaults.resetPasswordTemplate;
          case "emailChange" -> defaults.confirmEmailChangeTemplate;
          case "otp" -> defaults.otp.emailTemplate;
          case "authAlert" -> defaults.authAlert.emailTemplate;
          default -> throw new IllegalArgumentException("Unsupported auth email type: " + type);
        };
    if (selected == null) {
      return fallback;
    }
    return new CollectionSchema.EmailTemplate(
        text(selected.subject).isBlank() ? fallback.subject : selected.subject,
        text(selected.body).isBlank() ? fallback.body : selected.body);
  }

  private static Map<String, String> placeholders(
      Map<String, Object> record, Map<String, Object> request, Map<String, Object> settings) {
    Map<String, Object> meta = section(settings, "meta");
    Map<String, String> values = new LinkedHashMap<>();
    values.put("{APP_NAME}", defaultText(meta.get("appName"), "pocketbase-java"));
    values.put("{APP_URL}", defaultText(meta.get("appURL"), "http://127.0.0.1:8090"));
    values.put("{TOKEN}", text(request.get("token")));
    values.put("{OTP}", text(request.get("password")));
    values.put("{OTP_ID}", text(request.get("otpId")));
    values.put("{ALERT_INFO}", escapeHtml(text(request.get("alertInfo"))));
    if (record != null) {
      record.forEach((key, value) -> values.put("{RECORD:" + key + "}", escapeHtml(text(value))));
    }
    return values;
  }

  private static String resolve(String source, Map<String, String> placeholders) {
    String resolved = text(source);
    for (Map.Entry<String, String> entry : placeholders.entrySet()) {
      resolved = resolved.replace(entry.getKey(), entry.getValue());
    }
    return resolved;
  }

  private static SmtpMailer.Settings smtpSettings(Map<String, Object> smtp) {
    return new SmtpMailer.Settings(
        text(smtp.get("host")),
        Math.max(1, Math.min(65_535, intValue(smtp.get("port"), 587))),
        text(smtp.get("username")),
        text(smtp.get("password")),
        defaultText(smtp.get("authMethod"), "PLAIN"),
        truthy(smtp.get("tls")),
        text(smtp.get("localName")));
  }

  private static Map<String, Object> section(Map<String, Object> settings, String name) {
    if (settings != null && settings.get(name) instanceof Map<?, ?> map) {
      return Unsafe.stringObjectMap(map);
    }
    return Map.of();
  }

  private static String plainText(String html) {
    return HTML_TAG
        .matcher(
            text(html)
                .replace("<br>", "\n")
                .replace("<br/>", "\n")
                .replace("<br />", "\n")
                .replace("</p>", "\n"))
        .replaceAll("")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim();
  }

  private static String escapeHtml(String value) {
    return text(value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static String defaultText(Object value, String fallback) {
    String resolved = text(value);
    return resolved.isBlank() ? fallback : resolved;
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static boolean truthy(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    return value != null && Boolean.parseBoolean(String.valueOf(value));
  }

  private static int intValue(Object value, int fallback) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(text(value));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
