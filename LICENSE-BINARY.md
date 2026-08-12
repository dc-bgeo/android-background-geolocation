# BGeo Engine — Proprietary License

The open-source part of this package (the bridge/facade sources) is licensed
under the MIT License — see [`LICENSE`](./LICENSE).

This file governs the **closed-source engine binaries** bundled with or pulled
in by this package. They are NOT covered by the MIT grant: the bridge on its
own does not function without them.

Copyright (c) 2026 BGeo. All rights reserved.

This Part governs the closed-source engine artifact `dev.bgeo:bgeo-android`
(the "Engine"), however obtained — from Maven Central, from a bundled `libs/`
repository, or as part of another BGeo SDK package.

### 1. Grant

Subject to a valid, current license key issued by BGeo and to these terms, BGeo
grants you a non-exclusive, non-transferable license to install and use the
Engine in applications you develop and distribute.

- **Development / evaluation.** Debuggable builds run without a license key for
  evaluation.
- **Production.** A release build requires a valid license key bound to your
  application identifier and signing certificate. Without one, the SDK refuses
  to start and returns a `LICENSE_*` error.

A license key is valid for one year. Applications built with an SDK version
released during your license term keep working after the term ends; renewing
grants access to SDK versions released after it.

### 2. Restrictions

You may NOT: (a) decompile, disassemble, reverse-engineer, or otherwise attempt
to derive the source of the Engine; (b) redistribute, sublicense, rent, or
resell the Engine except as embedded in your own applications; (c) remove or
circumvent the license mechanism or any binding to your application; (d) share
a license key across applications not covered by it.

### 3. No Warranty

THE ENGINE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED. BGEO IS NOT LIABLE FOR ANY CLAIM, DAMAGES, OR OTHER LIABILITY ARISING
FROM ITS USE.

### 4. Termination

This Part terminates automatically if you breach its terms. On termination you
must stop using and distributing the Engine. The MIT grant in `LICENSE` is
unaffected.

---

For commercial licensing and support: https://bgeo.dev/?utm_source=github&utm_medium=license&utm_campaign=android
