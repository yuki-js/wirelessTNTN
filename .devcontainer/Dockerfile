FROM ghcr.io/yuki-js/dotfiles/dotimage:latest AS download
USER root
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates \
        wget \
        unzip \
        xz-utils \
        git \
        && rm -rf /var/lib/apt/lists/*

ARG CMDLINE_TOOLS_REV=13114758
RUN mkdir -p /opt/android-sdk \
    && cd /opt/android-sdk \
    && wget -qO cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_REV}_latest.zip" \
    && unzip -q cmdline-tools.zip -d /opt/android-sdk/cmdline-tools-tmp \
    && mkdir -p /opt/android-sdk/cmdline-tools/latest \
    && mv /opt/android-sdk/cmdline-tools-tmp/cmdline-tools/* /opt/android-sdk/cmdline-tools/latest/ \
    && rm -rf /opt/android-sdk/cmdline-tools-tmp cmdline-tools.zip

FROM ghcr.io/yuki-js/dotfiles/dotimage:latest
USER root
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        gnupg \
    && curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
        | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" \
        > /etc/apt/sources.list.d/adoptium.list \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        git \
        libasound2t64 \
        libatk-bridge2.0-0 \
        libatk1.0-0 \
        libcups2 \
        libdbus-1-3 \
        libdrm2 \
        libgbm1 \
        libgtk-3-0 \
        libnss3 \
        libx11-xcb1 \
        libxcomposite1 \
        libxdamage1 \
        libxfixes3 \
        libxkbcommon0 \
        libxrandr2 \
        libxshmfence1 \
        libxtst6 \
        temurin-17-jdk \
        xdg-utils \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64
ENV PATH="/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/build-tools/35.0.0:$PATH"

COPY --from=download --chown=user:user /opt/android-sdk/cmdline-tools/latest /opt/android-sdk/cmdline-tools/latest

RUN set -eux; \
    yes | sdkmanager --licenses \
    && sdkmanager --channel=0 "platform-tools" "platforms;android-35" "build-tools;35.0.0" \
    && sdkmanager --list_installed

RUN mkdir -p /home/user/.android \
    && chown -R user:user /home/user/.android /opt/android-sdk

USER user

CMD ["/bin/zsh"]
