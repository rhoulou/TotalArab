# TotalArab

Arabic content providers for CloudStream 3.

## Add the repo to CloudStream

Easy install page: **https://rhoulou.github.io/TotalArab/**

Or add the repo directly:

```
cloudstreamrepo://raw.githubusercontent.com/rhoulou/TotalArab/refs/heads/main/repo
```

Or add `https://raw.githubusercontent.com/rhoulou/TotalArab/main/repo.json` as a plugin repository URL.

## Providers

| Provider | Content | Notes |
| --- | --- | --- |
| Akwam | Movies, series, anime, Asian drama (Arabic) | Scrapes akwam.it directly from the phone. Falls back to the ak.sv alias, which always redirects to whichever akwam domain is currently live. Direct mp4 links. |
| WeCima | Movies, series (Arabic) | Scrapes wecima.cx directly from the phone. Falls back to wecima.watch/wecima.movie/wecima.click on domain changes. Embed servers (lulustream, doodstream, mixdrop, ...), seasons + episodes via the site's /ajax/Episode endpoint. |

## Dev

```sh
./depl.sh 1            # bump to v1, build, hash, push, verify served bytes
./depl.sh              # rebuild without version bump
```

Requires an Android SDK at `$ANDROID_HOME` (defaults to `/opt/android-sdk`) and a local Gradle 8.12 (`$GRADLE`, defaults to `/tmp/opencode/gradle-8.12/bin/gradle`). GitHub Actions CI builds and commits `*.cs3` automatically on push to `main`.
