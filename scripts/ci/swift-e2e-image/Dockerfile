ARG BASE_IMAGE=swift:6.3.3-jammy
FROM ${BASE_IMAGE}

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates zip \
    && rm -rf /var/lib/apt/lists/*
