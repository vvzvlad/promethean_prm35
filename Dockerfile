# Builds the APK with bare SDK build tools (aapt2, javac, d8, zipalign, apksigner), no Gradle.
# The final stage holds only /projector-remote.apk; `make apk` copies it out of a container.
FROM cimg/android:2026.08.1 AS build
USER root
RUN sdkmanager --install "platforms;android-28" > /dev/null
ENV BT=/home/circleci/android-sdk/build-tools/35.0.0 \
    JAR=/home/circleci/android-sdk/platforms/android-28/android.jar
WORKDIR /app
COPY AndroidManifest.xml debug.keystore ./
COPY res res
COPY assets assets
COPY src src
RUN set -e; \
    $BT/aapt2 compile --dir res -o res.zip; \
    $BT/aapt2 link -o unsigned.apk -I $JAR --manifest AndroidManifest.xml -A assets \
        --min-sdk-version 28 --target-sdk-version 28 --version-code 1 --version-name 1.0 \
        --java gen res.zip; \
    javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath $JAR -d classes \
        $(find src gen -name '*.java'); \
    $BT/d8 --min-api 28 --lib $JAR --output . $(find classes -name '*.class'); \
    zip -q -j unsigned.apk classes.dex; \
    $BT/zipalign -f -p 4 unsigned.apk aligned.apk; \
    mkdir /out; \
    $BT/apksigner sign --ks debug.keystore --ks-pass pass:android --out /out/projector-remote.apk aligned.apk; \
    $BT/apksigner verify /out/projector-remote.apk

FROM scratch
COPY --from=build /out/projector-remote.apk /
