# Third-party code embedded in the report

The report page is self-contained: these two libraries are served from the plugin jar instead of a CDN.
Files are the unmodified `dist` files of the npm packages; checksums were compared with the npm registry tarball and the CDN copies.

| Library | Version | Licence | File | SHA-256 |
|---------|---------|---------|------|---------|
| @tailwindcss/browser | 4.3.3 | MIT (`tailwindcss-LICENSE.txt`) | `tailwindcss-browser-4.3.3.js` | `a60c785630a06196808cbe79e6f7bdb4abcc8f4421a47b56f29338fc84805e3b` |
| JSZip | 3.10.1 | MIT or GPL-3.0-or-later, used under MIT (`jszip-LICENSE.md`) | `jszip-3.10.1.min.js` | `acc7e41455a80765b5fd9c7ee1b8078a6d160bbbca455aeae854de65c947d59e` |

To upgrade: `npm pack @tailwindcss/browser@<version> jszip@<version>`, copy `dist/index.global.js` and `dist/jszip.min.js`,
update the file names in `ExecutionReportResponder` and the table above.
