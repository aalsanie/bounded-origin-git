package io.github.aalsanie.boundedorigingit.cgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigingit.git.GitHashAlgorithm;
import io.github.aalsanie.boundedorigingit.git.GitObjectId;
import io.github.aalsanie.boundedorigingit.git.GitObjectStore;
import io.github.aalsanie.boundedorigingit.git.GitObjectType;
import io.github.aalsanie.boundedorigingit.git.GitRefName;
import io.github.aalsanie.boundedorigingit.git.RefGenerationStore;
import io.github.aalsanie.boundedorigingit.git.RepositoryUpdateEvent;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CgitRefResolverTest {
  @TempDir Path temporaryDirectory;

  @Test
  void resolvesDefaultFullAndShortPublishedRefsWithoutGit() throws Exception {
    Fixture fixture = fixture();
    GitObjectId main = fixture.object("main");
    GitObjectId tag = fixture.object("tag");
    fixture.refs().publish(
        List.of(
            create("refs/heads/main", main),
            create("refs/tags/v1", tag)),
        TrustLevel.TRUSTED);

    CgitRefResolver resolver =
        new CgitRefResolver(fixture.refs(), new GitRefName("refs/heads/main"));

    assertEquals(Optional.of(main), resolver.resolve(null));
    assertEquals(Optional.of(main), resolver.resolve(""));
    assertEquals(Optional.of(main), resolver.resolve("main"));
    assertEquals(Optional.of(main), resolver.resolve("refs/heads/main"));
    assertEquals(Optional.of(tag), resolver.resolve("v1"));
    assertEquals(Optional.of(tag), resolver.resolve("refs/tags/v1"));
  }

  @Test
  void rejectsAmbiguousShortNamesAndRevisionExpressions() throws Exception {
    Fixture fixture = fixture();
    GitObjectId branch = fixture.object("branch");
    GitObjectId tag = fixture.object("tag");
    fixture.refs().publish(
        List.of(
            create("refs/heads/release", branch),
            create("refs/tags/release", tag)),
        TrustLevel.TRUSTED);

    CgitRefResolver resolver =
        new CgitRefResolver(fixture.refs(), new GitRefName("refs/heads/main"));

    assertTrue(resolver.resolve("release").isEmpty());
    assertEquals(Optional.of(branch), resolver.resolve("refs/heads/release"));
    assertEquals(Optional.of(tag), resolver.resolve("refs/tags/release"));
    assertTrue(resolver.resolve("release~1").isEmpty());
    assertTrue(resolver.resolve("release^{commit}").isEmpty());
    assertTrue(resolver.resolve("does-not-exist").isEmpty());
  }

  @Test
  void followsOnlyAtomicallyPublishedGeneration() throws Exception {
    Fixture fixture = fixture();
    GitObjectId first = fixture.object("first");
    GitObjectId second = fixture.object("second");
    fixture.refs().publish(
        List.of(create("refs/heads/main", first)), TrustLevel.TRUSTED);
    CgitRefResolver resolver =
        new CgitRefResolver(fixture.refs(), new GitRefName("refs/heads/main"));

    assertEquals(Optional.of(first), resolver.resolve("main"));

    fixture.refs().publish(
        List.of(
            new RepositoryUpdateEvent(
                "project",
                new GitRefName("refs/heads/main"),
                Optional.of(first),
                Optional.of(second))),
        TrustLevel.TRUSTED);

    assertEquals(Optional.of(second), resolver.resolve("main"));
  }

  private Fixture fixture() throws Exception {
    GitObjectStore objects =
        new GitObjectStore(
            temporaryDirectory.resolve("objects"), GitHashAlgorithm.SHA1, 1024);
    RefGenerationStore refs =
        new RefGenerationStore(
            temporaryDirectory.resolve("refs"), "project", objects, 16, 8);
    return new Fixture(objects, refs);
  }

  private static RepositoryUpdateEvent create(String ref, GitObjectId after) {
    return new RepositoryUpdateEvent(
        "project", new GitRefName(ref), Optional.empty(), Optional.of(after));
  }

  private static GitObjectId objectId(byte[] payload) {
    MessageDigest digest = MessageDigest.getInstance("SHA-1");
    digest.update(
        ("blob " + payload.length + "\0").getBytes(StandardCharsets.US_ASCII));
    digest.update(payload);
    return new GitObjectId(
        GitHashAlgorithm.SHA1, HexFormat.of().formatHex(digest.digest()));
  }

  private record Fixture(GitObjectStore objects, RefGenerationStore refs) {
    GitObjectId object(String value) throws Exception {
      byte[] payload = (value + "\n").getBytes(StandardCharsets.UTF_8);
      GitObjectId id = objectId(payload);
      objects.ingest(
          id, GitObjectType.BLOB, payload.length, new ByteArrayInputStream(payload));
      return id;
    }
  }
}
