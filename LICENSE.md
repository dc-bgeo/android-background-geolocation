# BGeo SDK — License

Copyright (c) 2026 BGeo.

This SDK is licensed in two parts:

| Part | What | License |
| --- | --- | --- |
| **Facade** (open) | `sdk/src/`, `sdk/build.gradle.kts`, `example/` | MIT — Part A |
| **Engine** (closed) | the `dev.bgeo:bgeo-android` AAR this facade depends on | Proprietary — Part B |

The Maven artifact `dev.bgeo:background-geolocation` carries both license
entries in its POM for that reason: its own source is MIT, and it cannot run
without the engine, which is not.

---

## Part A — Facade (MIT License)

Copyright (c) 2026 BGeo

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

This MIT grant covers the facade only. It does not grant any right in the
engine, which remains governed by Part B — the facade on its own does not
function without it.

---

## Part B — Engine (Proprietary License Agreement)

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
must stop using and distributing the Engine. The MIT grant in Part A is
unaffected.

---

For commercial licensing and support: https://bgeo.dev
