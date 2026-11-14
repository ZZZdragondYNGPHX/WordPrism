# Bundled third-party notices

`manifest.json` identifies the dependencies used by the Android app's resolved release runtime, the standalone backend runtime, the pinned Rust dependency graph and the two bundled fonts. Cargo entries conservatively include all packages in `Cargo.lock`; they do not imply that every target-specific dependency is linked into each ABI.

Maven license declarations are read from the resolved POM, including parent inheritance. Artifact `LICENSE` and `NOTICE` files are preserved where present; the common Apache 2.0 text is in `Apache-2.0.txt`. The manifest records source URLs rather than local cache paths.

The memory engine is the unmodified `fsrs` 6.6.2 crate, distributed under BSD-3-Clause. For crates offering MIT as an alternative, this distribution selects MIT. `unicode-ident` also carries the Unicode-3.0 terms. For `priority-queue` 2.7.0, this distribution selects MPL-2.0 and includes the exact, unmodified source archive at `rust/priority-queue-2.7.0/priority-queue-2.7.0.crate`, the license text and its SHA-256 in the manifest. No LGPL option is selected.

Atkinson Hyperlegible Next and Mono are separate version 2.001 font binaries under SIL Open Font License 1.1. Both copyright notices and license texts are retained under `fonts/`; their binary hashes are in the manifest. The app packages the existing font binaries without modification.

Regenerate using `core/scripts/collect-notices.py` after resolving dependencies; see `docs/core/running.md` for the required inputs. The Android platform AAR includes this directory as `assets/wordprism-notices/`, which is carried into the app APK. This directory contains third-party notices only and does not assign a license to WordPrism's own code.
