# Builds the projector remote APK in Docker and installs it onto the Android TV box over network adb.
# `make` or `make help` lists the targets. TV selects the box: make install TV=10.31.41.106:5555
# The docker CLI may point at a remote daemon, so the APK comes back through `docker cp`, not a bind mount.

TV ?= 10.31.41.106:5555
PKG := xyz.vvzvlad.projectorremote
IMAGE := projector-remote-build
APK := build/projector-remote.apk
ADB := adb -s $(TV)
SHELL := /bin/bash

.PHONY: help apk install connect logs

help:
	@grep -E '^[a-z]+:.*## ' $(MAKEFILE_LIST) | sed 's/:.*## /\t/'

apk: ## Build build/projector-remote.apk in Docker
	docker build -t $(IMAGE) .
	mkdir -p build
	id=$$(docker create $(IMAGE) none) && { docker cp $$id:/projector-remote.apk $(APK); r=$$?; docker rm $$id >/dev/null; exit $$r; }

connect:
	adb connect $(TV) >/dev/null

install: apk connect ## Build, install over adb and start the app
	$(ADB) install -r $(APK)
	$(ADB) shell am start -n $(PKG)/.MainActivity

logs: connect ## Show the app's log lines
	$(ADB) logcat -d -s ProjectorRemote
