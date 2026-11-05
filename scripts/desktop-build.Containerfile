# Build image for scripts/desktop-dist.sh: Ubuntu 24.04 (glibc 2.39), the packages the Tauri desktop build and the
# AppImage need, Rust and tauri-cli. Built once; the script rebuilds it only if the image is missing or this file changed.
FROM docker.io/library/ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive CARGO_HOME=/cargo RUSTUP_HOME=/cargo/rustup PATH=/cargo/bin:$PATH
RUN apt-get update -qq && apt-get install -y -qq curl wget ca-certificates file build-essential pkg-config dpkg-dev patchelf \
      xz-utils libssl-dev libdbus-1-dev libgtk-3-dev libwebkit2gtk-4.1-dev libgtk-layer-shell-dev \
      libayatana-appindicator3-dev librsvg2-dev libxkbcommon-x11-0 \
    && rm -rf /var/lib/apt/lists/*
RUN curl -sSf https://sh.rustup.rs | sh -s -- -y -q --profile minimal --no-modify-path \
    && cargo install tauri-cli --version "^2" --locked \
    && chmod -R a+rwX /cargo
