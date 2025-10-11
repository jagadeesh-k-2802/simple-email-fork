# Change Log

All notable changes to this project will be documented in this file.
See [Conventional Commits](https://conventionalcommits.org) for commit guidelines.

## [1.5.4] (https://github.com/jagadeesh-k-2802/simple-email-fork/compare/v1.5.3...v1.5.4) (2025-10-11)
* Fixed A12+ Crash on adding a new email account
* Added text size setting
* By default 'show images' is enabled in mail
* Added `Release Test` gradle config

## [1.5.3](https://framagit.org/dystopia-project/simple-email/compare/v1.5.2...v1.5.3) (2023-09-04)


### Bug Fixes

* synchronization from message view ([6752a86](https://framagit.org/dystopia-project/simple-email/commit/6752a8618646f2324be89c49ea57929b1c5a56cd))

## [1.5.2](https://framagit.org/dystopia-project/simple-email/compare/v1.5.1...v1.5.2) (2023-09-03)


### Bug Fixes

* crash on change between message view ([3523d34](https://framagit.org/dystopia-project/simple-email/commit/3523d341dd6104a84273589af6fd8cc7278c1b91))
* new permission 'POST_NOTIFICATIONS' required by android >= 13 ([2878d5b](https://framagit.org/dystopia-project/simple-email/commit/2878d5b74c006f53957c61845e146a0b11a07378))

## [1.5.1](https://framagit.org/dystopia-project/simple-email/compare/v1.5.0...v1.5.1) (2023-08-26)


### Bug Fixes

* **compose:** Unable to insert attachments ([bfcbb19](https://framagit.org/dystopia-project/simple-email/commit/bfcbb19fa7a638f8ffc6562194b6e7405dea807c))

# [1.5.0](https://framagit.org/dystopia-project/simple-email/compare/v1.4.0...v1.5.0) (2023-08-26)


### Bug Fixes

* Open and share/save attachments on android >= 10 ([fd22a16](https://framagit.org/dystopia-project/simple-email/commit/fd22a1671667d572fec9d0794bbd6c7cbf4453d4))


### Features

* **design:** white/black action bar + status bar ([2f7bdfa](https://framagit.org/dystopia-project/simple-email/commit/2f7bdfa60b706f7dcc9a83d094eedb697b57721b))
* display downloading attachment status ([930adac](https://framagit.org/dystopia-project/simple-email/commit/930adac7f17b19da4a191b1cb617d803f393f350))
* Start migration to Kotlin ([5035274](https://framagit.org/dystopia-project/simple-email/commit/5035274e91975c18351b58eee5ea584f94275af3))

# [1.4.0](https://framagit.org/dystopia-project/simple-email/compare/v1.3.0...v1.4.0) (2020-11-09)


### Bug Fixes

* compatibly with SDK API 21 ([9005f7c](https://framagit.org/dystopia-project/simple-email/commit/9005f7c69aad4f7734133f3f636d4bcfd97f5ead))
* crash on uninitialized cursor instance ([8071dc3](https://framagit.org/dystopia-project/simple-email/commit/8071dc32cc50166938f005294e1a360d995f2eaf)), closes [#15](https://framagit.org/dystopia-project/simple-email/issues/15)
* gmail authentication over non-oAuth ([15cc220](https://framagit.org/dystopia-project/simple-email/commit/15cc220fb064cc2d2032b73d89f263e37ae159fd))
* wrong format on compose email addresses ([d4a1c8c](https://framagit.org/dystopia-project/simple-email/commit/d4a1c8ca204c0a7c6dfdd9583031f90a92183577))
* **ui:** improve message items view to compact and unread ([7389734](https://framagit.org/dystopia-project/simple-email/commit/738973499224ef09f4bc7d831ff7b7b49685d7cd))
* **ui:** increase distance on refreshable ([ce661ca](https://framagit.org/dystopia-project/simple-email/commit/ce661ca32b4d672eb54c9e095ae9bfdff4b0efeb))


### Features

* improve swipe actions in archive/trash folders ([0f76b2b](https://framagit.org/dystopia-project/simple-email/commit/0f76b2bd006dc2a7c8e46129b5513ef474107e12))
* update color picker dialog ([c1a2380](https://framagit.org/dystopia-project/simple-email/commit/c1a2380d2c42c1232498a1eafc8cb32ec8b035d9))

# [1.3.0](https://framagit.org/dystopia-project/simple-email/compare/v1.2.1...v1.3.0) (2018-12-08)


### Bug Fixes

* change app Invite to share because require google account ([8299d54](https://framagit.org/dystopia-project/simple-email/commit/8299d54))
* check error on open attachment ([87a22a9](https://framagit.org/dystopia-project/simple-email/commit/87a22a9)), closes [#10](https://framagit.org/dystopia-project/simple-email/issues/10)
* prevent flickering on message view ([d6294c6](https://framagit.org/dystopia-project/simple-email/commit/d6294c6))


### Features

* better layout for the message list ([73aa80d](https://framagit.org/dystopia-project/simple-email/commit/73aa80d)), closes [#8](https://framagit.org/dystopia-project/simple-email/issues/8)
* implement refresh messages list ([f5973df](https://framagit.org/dystopia-project/simple-email/commit/f5973df))
* move color picker account outside of advanced ([7fb625c](https://framagit.org/dystopia-project/simple-email/commit/7fb625c))

## [1.2.1](https://framagit.org/dystopia-project/simple-email/compare/v1.2.0...v1.2.1) (2018-11-23)


### Bug Fixes

* check compatibility on notification settings ([36b3e25](https://framagit.org/dystopia-project/simple-email/commit/36b3e25))
* crash on public notification without color ([4bc5d9a](https://framagit.org/dystopia-project/simple-email/commit/4bc5d9a))

# [1.2.0](https://framagit.org/dystopia-project/simple-email/compare/v1.1.2...v1.2.0) (2018-11-20)


### Bug Fixes

* missing null check on refactor notification ([3318b38](https://framagit.org/dystopia-project/simple-email/commit/3318b38))


### Features

* improve messages UI ([1e9c5e8](https://framagit.org/dystopia-project/simple-email/commit/1e9c5e8))

## [1.1.2](https://framagit.org/dystopia-project/simple-email/compare/v1.1.1...v1.1.2) (2018-11-20)


### Bug Fixes

* cancel single notification per account ([656e51e](https://framagit.org/dystopia-project/simple-email/commit/656e51e))
* focus body on reply message ([4b6e230](https://framagit.org/dystopia-project/simple-email/commit/4b6e230))
* message when no have identities ([4595289](https://framagit.org/dystopia-project/simple-email/commit/4595289))
* wrong riseup smtp port ([ecd870c](https://framagit.org/dystopia-project/simple-email/commit/ecd870c))

## [1.1.1](https://framagit.org/dystopia-project/simple-email/compare/v1.1.0...v1.1.1) (2018-11-14)


### Bug Fixes

* crash on new notifications with account without color ([1e0665a](https://framagit.org/dystopia-project/simple-email/commit/1e0665a))


# [1.1.0](https://framagit.org/dystopia-project/simple-email/compare/v1.0.0...v1.1.0) (2018-11-12)


### Bug Fixes

* message item view when not have size ([2d44ea5](https://framagit.org/dystopia-project/simple-email/commit/2d44ea5))
* trash messages are showing in threads ([573aba1](https://framagit.org/dystopia-project/simple-email/commit/573aba1))

### Features

* improve new mail notifications per account ([0149a10](https://framagit.org/dystopia-project/simple-email/commit/0149a10))


# [1.0.0](https://framagit.org/dystopia-project/simple-email) (2018/11/08)


### Bug Fixes

* hide button after show images (#2790b76d)
* check on empty search fallback (#6e9b5992)
* check if has storage framework (#1a07f5fa)

### Features

* add riseup provider (#4126bae3)
* add text when no have accounts yet (#2d15cd32)
* remove Eula annoying initial view (#a794fc0e)
* improve message interface (#de7dc808)
* change 'enable' advanced option to 'enable sync' to be clear (#fc5949c7)
* set account name on compose (#e4a97052)
* change to switch on advance options (#236d39b1)
