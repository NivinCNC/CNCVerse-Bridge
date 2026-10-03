# libs

## cloudstream-api.jar (patched)

`cloudstream-api.jar` is `cloudstream-api.original.jar` with one bytecode change, made by
`patcher/PatchCloudstreamApi.java`:

- In `ExtractorApiKt.loadExtractor`, the fuzzy fallback
  `Levenshtein.partialRatio$default(...) > 80` (run against every registered extractor when no
  mainUrl is a prefix of the link) now calls
  `com.cncverse.stremiobridge.plugin.FastExtractorMatch.partialRatioForExtractor` (same descriptor).

`FastExtractorMatch` skips the edit-distance matrix when a character-overlap bound proves the
score can't exceed 80, and otherwise calls the original `partialRatio`, so every decision is
unchanged (checked on 218k URL/extractor pairs: 0 mismatches, ~18x faster). This was ~20% of
the server's CPU.

It also routes `logError`'s `printStackTrace()` (ArchComponentExtKt) to
`PluginErrorLog.printStackTrace`: one line per distinct error per minute instead of a full trace
for every episode with an empty date / cancelled request (~35k journal lines a minute).

## NiceHttp-0.4.16-patched.jar

NiceHttp 0.4.16 (`NiceHttp-0.4.16.original.jar`, from Maven) with its `printStackTrace()` calls
(ContinuationCallback, NiceResponse, OkioHelper) routed to `PluginErrorLog` the same way. Vendored
because of that; its own dependencies (org.json, jsoup 1.21.2) are declared in shared/build.gradle.kts.
Patch it with the same patcher: `PatchCloudstreamApi NiceHttp-0.4.16.original.jar NiceHttp-0.4.16-patched.jar`.

### Updating the jar

1. Replace `cloudstream-api.original.jar` with the new upstream jar.
2. Re-run the patcher (it fails if a rule patches an unexpected number of call sites):

```bash
ASM=~/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.8/*/asm-9.8.jar
javac -d /tmp/pcs -cp $ASM patcher/PatchCloudstreamApi.java
java -cp "$ASM:/tmp/pcs" PatchCloudstreamApi cloudstream-api.original.jar cloudstream-api.jar
```

(On Windows use `;` as the classpath separator.)
