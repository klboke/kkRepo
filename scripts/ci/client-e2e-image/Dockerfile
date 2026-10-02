# syntax=docker/dockerfile:1
# Version families match live-compat.yml. Daily cache epochs refresh the moving
# stable channels; the resolved versions are recorded and checked in each image.
FROM node:24-bookworm-slim AS node
FROM golang:1.25-bookworm AS go
FROM mcr.microsoft.com/dotnet/sdk:8.0-bookworm-slim AS dotnet
FROM ruby:3.4-slim-bookworm AS ruby
FROM php:8.4-cli-bookworm AS php
FROM composer:2 AS composer
FROM maven:3.9-eclipse-temurin-25 AS maven
FROM docker:cli AS docker
FROM ghcr.io/astral-sh/uv:latest AS uv

FROM eclipse-temurin:25-jdk-noble AS base
SHELL ["/bin/bash", "-euo", "pipefail", "-c"]
RUN apt-get update && apt-get install -y --no-install-recommends \
      bash ca-certificates curl git unzip zip xz-utils jq build-essential \
      libicu74 libssl3t64 libyaml-0-2 libreadline8t64 libgmp10 \
      libedit2 libxml2 libsqlite3-0 libsodium23 libargon2-1 libonig5 libcurl4t64 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=uv /uv /usr/local/bin/uv
COPY --from=maven /usr/share/maven /opt/maven
COPY --from=docker /usr/local/bin/docker /usr/local/bin/docker
ENV UV_PYTHON_INSTALL_DIR=/opt/python \
    PATH=/opt/java/openjdk/bin:/opt/python-env/bin:/opt/maven/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    MAVEN_HOME=/opt/maven \
    DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1 DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1
RUN uv python install 3.13 && uv venv --python 3.13 --seed /opt/python-env
COPY install.sh verify.sh /opt/e2e/
ENTRYPOINT []
CMD ["bash"]

FROM base AS core
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
COPY --from=go /usr/local/go /usr/local/go
COPY --from=dotnet /usr/share/dotnet /usr/share/dotnet
COPY --from=ruby /usr/local/ /usr/local/
COPY --from=php /usr/local/bin/php /usr/local/bin/php
COPY --from=php /usr/local/lib/php /usr/local/lib/php
COPY --from=php /usr/local/etc/php /usr/local/etc/php
COPY --from=composer /usr/bin/composer /usr/local/bin/composer
ENV PATH=/opt/java/openjdk/bin:/opt/python-env/bin:/opt/maven/bin:/opt/flutter/bin:/opt/cargo/bin:/usr/local/go/bin:/usr/share/dotnet:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    CARGO_HOME=/opt/cargo RUSTUP_HOME=/opt/rustup DOTNET_ROOT=/usr/share/dotnet \
    COMPOSER_ALLOW_SUPERUSER=1
RUN ldconfig && bash /opt/e2e/install.sh core && bash /opt/e2e/verify.sh core > /opt/e2e/versions.txt

FROM base AS system
ENV CONDA_BIN=/opt/conda/bin/conda \
    TERRAFORM_013_BIN=/opt/terraform/0.13.7/terraform \
    TERRAFORM_CURRENT_BIN=/opt/terraform/1.15.8/terraform \
    ANSIBLE_GALAXY_BINS=2.9=/opt/ansible/2.9/bin/ansible-galaxy,current=/opt/ansible/current/bin/ansible-galaxy \
    ANSIBLE_E2E_REQUIRE_2_9_CURRENT=true
RUN bash /opt/e2e/install.sh system && bash /opt/e2e/verify.sh system > /opt/e2e/versions.txt

FROM base AS swift
RUN bash /opt/e2e/verify.sh swift > /opt/e2e/versions.txt
