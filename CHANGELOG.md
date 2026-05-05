# Changelog

## [0.6.1](https://github.com/alcantaraleo/graphus/compare/v0.6.0...v0.6.1) (2026-05-05)


### Bug Fixes

* **ci:** use localhost for Chroma in publish benchmark ([482aaf4](https://github.com/alcantaraleo/graphus/commit/482aaf4334f1415ab888a0b59de55e2901e03daa))
* **ci:** use localhost for Chroma in publish benchmark ([eee6df8](https://github.com/alcantaraleo/graphus/commit/eee6df80ad6030f779d4685f10bd23da0fe1dd66))

## [0.6.0](https://github.com/alcantaraleo/graphus/compare/v0.5.1...v0.6.0) (2026-05-05)


### Features

* **cli:** [#35](https://github.com/alcantaraleo/graphus/issues/35) add index benchmark JSON and release PERFORMANCE artifact ([ac8ee41](https://github.com/alcantaraleo/graphus/commit/ac8ee41e992adfa83b5f5970c52000be8bdd2a2a))
* **cli:** add index benchmark JSON and release PERFORMANCE artifact ([69d377d](https://github.com/alcantaraleo/graphus/commit/69d377d706b0305e30614c59c72e98b148842ce8))
* **graphus:** [#33](https://github.com/alcantaraleo/graphus/issues/33) SQLite vector backend and persisted config.json ([865312b](https://github.com/alcantaraleo/graphus/commit/865312ba5ad476a7c30e8939764f79f085a97132))
* **graphus:** add multi-module workspace detection and module-tagged indexing ([186d61d](https://github.com/alcantaraleo/graphus/commit/186d61db9eca7caeed51c4df5225c592198d648d))
* **graphus:** multi-module workspace detection and module-tagged indexing ([f9111e9](https://github.com/alcantaraleo/graphus/commit/f9111e9a77ea52848edd8ef1d026be98b0b73a55))
* **graphus:** SQLite vector backend and config.json persistence ([bb34513](https://github.com/alcantaraleo/graphus/commit/bb34513290c69b63bc551981c917b518ad956b16))


### Bug Fixes

* **cli:** output version when --version flag is passed ([b7aec74](https://github.com/alcantaraleo/graphus/commit/b7aec741ba41e30db68905649dc164e476693771))
* **cli:** output version when --version flag is passed ([a4d1189](https://github.com/alcantaraleo/graphus/commit/a4d11891d85023981a8b8ebb15e502bbc33d81ce))
* **deps:** resolve open Dependabot security alerts ([f9ba43d](https://github.com/alcantaraleo/graphus/commit/f9ba43d999813243237060e6e6502c26a126bd4b))
* **deps:** resolve open Dependabot security alerts ([8357afc](https://github.com/alcantaraleo/graphus/commit/8357afc350e9d9ebf7e36c085e27641b2a786074))
* **graphus-indexer:** [#33](https://github.com/alcantaraleo/graphus/issues/33) correct metadata number coercion after SQLite round-trip ([5d23bac](https://github.com/alcantaraleo/graphus/commit/5d23bac242d8e033a898b1d5e1d90aad805cd426))

## [0.5.1](https://github.com/alcantaraleo/graphus/compare/v0.5.0...v0.5.1) (2026-05-05)


### Bug Fixes

* **build:** migrate shadow plugin for Gradle 9 compatibility ([3d0572c](https://github.com/alcantaraleo/graphus/commit/3d0572cb7ebcc1ca68bac10683f43f2684d85162))
* **deps:** resolve vulnerability overrides and centralize cursor config ([a84ede4](https://github.com/alcantaraleo/graphus/commit/a84ede49c8c42443a55dff3145318496aad32691))
* **deps:** resolve vulnerability overrides and centralize cursor config ([e580d76](https://github.com/alcantaraleo/graphus/commit/e580d76cc822bad9a3ffc04b728678d949f29151))
* **workflow:** harden dependabot auto-close signal ([36255bd](https://github.com/alcantaraleo/graphus/commit/36255bd28963bcf52d72a36d4e294c9beb64c241))
* **workflow:** harden dependabot auto-close signal ([a4858fa](https://github.com/alcantaraleo/graphus/commit/a4858fa759a7442faca2de85b16e27b1ea654a49))

## [0.5.0](https://github.com/alcantaraleo/graphus/compare/v0.4.0...v0.5.0) (2026-05-01)


### Features

* **workflow:** automate issue lifecycle labels through plan, PR, and release ([93474ea](https://github.com/alcantaraleo/graphus/commit/93474ea324885d2697bfa8907ad2296ee8745795))
* **workflow:** automate issue lifecycle labels through plan, PR, and release ([cd99f98](https://github.com/alcantaraleo/graphus/commit/cd99f98700a57e2757644cb1798f4ca9a0435526))


### Bug Fixes

* **cli:** disable CLI Maven publication tasks ([c61e937](https://github.com/alcantaraleo/graphus/commit/c61e9378ef0e7222332bcae3028a127b4a517a16))
* **cli:** disable CLI Maven publication tasks ([60b5bbf](https://github.com/alcantaraleo/graphus/commit/60b5bbf2237d60f2e6f04a19cf02bb8f2a69fce5))

## [0.4.0](https://github.com/alcantaraleo/graphus/compare/v0.3.0...v0.4.0) (2026-05-01)


### Features

* **cli:** default --collection to directory name when omitted ([339ae22](https://github.com/alcantaraleo/graphus/commit/339ae22cad6ea12333a397ef3a3b60794b2253ce))
* **cli:** default --collection to directory name when omitted ([121c872](https://github.com/alcantaraleo/graphus/commit/121c872064a930f94d717c2f23f19540cd2e77ce))

## [0.3.0](https://github.com/alcantaraleo/graphus/compare/v0.2.0...v0.3.0) (2026-04-30)


### Features

* **cli:** improve terminal progress UI with ANSI colors ([56823a2](https://github.com/alcantaraleo/graphus/commit/56823a2696ddd19163ae374f31d4e60ebfa892e6))
* **cli:** improve terminal progress UI with ANSI colors ([c57a631](https://github.com/alcantaraleo/graphus/commit/c57a631fd807a34bc260e907ddb3d9ef9fefb30c))
* **parser:** add Guice-aware symbol extraction and indexing ([2b867a4](https://github.com/alcantaraleo/graphus/commit/2b867a4c1f09b8a19577a30943b44b7c3225c8c0))
* **parser:** add Guice-aware symbol extraction and indexing ([bfa76a7](https://github.com/alcantaraleo/graphus/commit/bfa76a79c4fe7b762987f418fe911c8628572a9c))

## [0.2.0](https://github.com/alcantaraleo/graphus/compare/v0.1.0...v0.2.0) (2026-04-30)


### Features

* **cli:** add indexing phase timing output ([f163f6e](https://github.com/alcantaraleo/graphus/commit/f163f6ee0316a24a959d0e3893c49704b56a54ae))
* **cli:** improve indexing throughput and automate Homebrew tap release updates ([303093a](https://github.com/alcantaraleo/graphus/commit/303093a6e97133939ec8a07bb6b2369d6fdf997b))
