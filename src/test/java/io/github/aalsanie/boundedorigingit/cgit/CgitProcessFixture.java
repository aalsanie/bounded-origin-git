package io.github.aalsanie.boundedorigingit.cgit;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CgitProcessFixture {
  private CgitProcessFixture() {}

  public static void main(String[] arguments) throws Exception {
    String query = System.getenv("QUERY_STRING");
    String config = Files.readString(
        Path.of(System.getenv("CGIT_CONFIG")), StandardCharsets.UTF_8);
    boolean safeConfig =
        config.contains("cache-size=0")
            && config.contains("enable-http-clone=0")
            && config.contains("repo.url=project")
            && config.contains("repo.path=");

    String search = queryValue(query, "q");
    if (search != null && search.startsWith("wait:")) {
      Path pidFile = Path.of(search.substring("wait:".length()));
      Files.writeString(
          pidFile,
          Long.toString(ProcessHandle.current().pid()),
          StandardCharsets.US_ASCII);
      while (true) {
        Thread.sleep(1000);
      }
    }

    if (query.contains("q=large")) {
      System.out.print("Content-Type: text/plain\r\n\r\n");
      System.out.print("x".repeat(4096));
      System.out.flush();
      return;
    }

    if (query.contains("p=tag")) {
      System.out.print("Status: 404 Not Found\r\n");
    } else {
      System.out.print("Status: 200 OK\r\n");
    }
    System.out.print("Content-Type: text/plain\r\n");
    System.out.print("Set-Cookie: ignored=yes\r\n");
    System.out.print("\r\n");
    System.out.print(query + "\n");
    System.out.print("config-safe=" + safeConfig + "\n");
    System.out.flush();
  }

  private static String queryValue(String query, String name) {
    for (String parameter : query.split("&")) {
      int separator = parameter.indexOf('=');
      if (separator < 0) {
        continue;
      }
      String key =
          URLDecoder.decode(parameter.substring(0, separator), StandardCharsets.UTF_8);
      if (name.equals(key)) {
        return URLDecoder.decode(parameter.substring(separator + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }
}
