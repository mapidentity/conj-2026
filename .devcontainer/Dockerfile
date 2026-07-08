FROM mcr.microsoft.com/devcontainers/base:trixie
ARG DEBIAN_FRONTEND=noninteractive

ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US:en
ENV LC_ALL=en_US.UTF-8

# Generating locales for better cli experience.
RUN sed -i -e 's/# en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen && \
    dpkg-reconfigure --frontend=noninteractive locales && \
    update-locale LANG=en_US.UTF-8
ENV LANGUAGE=en_US.UTF-8 LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8

# Install necessary packages and tools (some optional but useful).
RUN apt-get update && apt-get install -y \
    apt-transport-https \
    apt-utils \
    bash-completion \
    bat \
    btop \
    build-essential \
    ca-certificates \
    curl \
    fd-find \
    fzf \
    git \
    gnupg \
    grep \
    htop \
    httpie \
    inotify-tools \
    iotop \
    jq \
    locales \
    libnss3-tools \
    libvips-tools \
    lsb-release \
    netcat-openbsd \
    nethogs \
    ripgrep \
    rlwrap \
    ssh \
    strace \
    sysstat \
    tcpdump \
    tmux \
    unzip \
    valgrind \
    wget \
    wireshark-common \
    yq \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Install Java
RUN wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor | tee /etc/apt/trusted.gpg.d/adoptium.gpg > /dev/null \
    && echo "deb https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" | tee /etc/apt/sources.list.d/adoptium.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
    temurin-25-jdk \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*
# <arch> is Docker's TARGETARCH (amd64 or arm64); using it keeps JAVA_HOME
# correct on Intel runners and Apple Silicon alike.
ARG TARGETARCH
ENV JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-${TARGETARCH}

# Install Clojure
RUN curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh \
    && chmod +x linux-install.sh \
    && ./linux-install.sh \
    && clojure -M -e '(println "Clojure installed")'

### ===================================================
### Not strictly needed, but very common Clojure tools.
### ===================================================

# Install babashka
RUN curl -s https://raw.githubusercontent.com/babashka/babashka/master/install | bash

# Install CLJ-Kondo linter
RUN curl -s https://raw.githubusercontent.com/clj-kondo/clj-kondo/master/script/install-clj-kondo | bash

# Install Clojure zprint formatter
RUN wget -q https://github.com/kkinnear/zprint/releases/download/1.3.0/zprintl-1.3.0 \
    && mv zprintl-1.3.0 /usr/local/bin/zprint \
    && chmod +x /usr/local/bin/zprint

### ====================================================
### Below are all optional auxiliary tools.
### This happens to be for a Claude Code setup with MCP.
### Replace or complement with your own favorite setup.
### ====================================================

# Install Claude Code
RUN curl -fsSL https://claude.ai/install.sh | bash
# Make --dangerously-skip-permissions work for Claude Code
ENV IS_SANDBOX=1

# Install Chrome
RUN curl -fSsL https://dl.google.com/linux/linux_signing_key.pub | gpg --dearmor | tee /usr/share/keyrings/google-chrome.gpg > /dev/null \
    && echo deb [arch=amd64 signed-by=/usr/share/keyrings/google-chrome.gpg] http://dl.google.com/linux/chrome/deb/ stable main | tee /etc/apt/sources.list.d/google-chrome.list \
    && apt update && apt install google-chrome-stable -y
RUN wget -q https://chromedriver.storage.googleapis.com/114.0.5735.90/chromedriver_linux64.zip \
    && unzip chromedriver_linux64.zip \
    && mv chromedriver /usr/bin/chromedriver \
    && chown root:root /usr/bin/chromedriver \
    && chmod +x /usr/bin/chromedriver \
    && rm -f chromedriver_linux64.zip
ENV CHROMEBIN=/usr/bin/google-chrome

# Install Clojure MCP
RUN clojure -Ttools install-latest :lib io.github.bhauman/clojure-mcp :as mcp

# Install NodeJS
# Install Playwright MCP with Chromium and Lighthouse CI
# Download and install nvm:
ENV NVM_DIR=/usr/local/nvm
RUN mkdir -p "$NVM_DIR" \
    && curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.3/install.sh | bash \
    && . "$NVM_DIR/nvm.sh" \
    && nvm install --lts \
    && nvm alias default lts/* \
    # make node/npm/npx available to non-login shells and later layers
    && ln -s "$NVM_DIR/versions/node/$(nvm version)/bin/node" /usr/local/bin/node \
    && ln -s "$NVM_DIR/versions/node/$(nvm version)/bin/npm"  /usr/local/bin/npm \
    && ln -s "$NVM_DIR/versions/node/$(nvm version)/bin/npx"  /usr/local/bin/npx \
    && npm install -g @playwright/test @playwright/mcp @lhci/cli tailwindcss @tailwindcss/cli \
    && ln -s "$NVM_DIR/versions/node/$(nvm version)/bin/playwright"  /usr/local/bin/playwright \
    && ln -s "$NVM_DIR/versions/node/$(nvm version)/bin/playwright-mcp"  /usr/local/bin/playwright-mcp \
    && ln -s "$NVM_DIR/versions/node/$(nvm version)/bin/lhci"  /usr/local/bin/lhci \
    && ln -s "$NVM_DIR/versions/node/$(nvm version)/bin/tailwindcss" /usr/local/bin/tailwindcss \
    && ln -s "$NVM_DIR/versions/node/$(nvm version)/lib/node_modules" /usr/local/lib/node_modules \
    && playwright install --with-deps
ENV NODE_PATH=/usr/local/lib/node_modules
