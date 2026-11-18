#!/usr/bin/env bash
# Builds the "STEM & diagrams" render bundle the app downloads (Settings → STEM & diagrams).
#
#   bash scripts/render_bundle/build_render_bundle.sh
#
# Output: scripts/render_bundle/out/antigem-render-bundle-v1.zip (+ .sha256), git-ignored.
# Upload that zip to a GitHub release with the tag in BUNDLE_TAG (same file name), then put the printed
# SHA-256 in RenderBundle.BUNDLE_SHA256 (app/src/main/java/ai/antigem/data/render/RenderBundle.kt).
#
# Every library is pinned to an exact version: the app tells the model these exact versions.
# Changing a version = new bundle tag (render-v2, ...) + new URL / SHA-256 / versions in RenderBundle.kt.
set -euo pipefail

BUNDLE_VERSION=1
BUNDLE_TAG="render-v${BUNDLE_VERSION}"
ZIP_NAME="antigem-render-bundle-v${BUNDLE_VERSION}.zip"

MERMAID_V="10.9.8"          # same major as the CDN copy the app already uses (mermaid@10)
SMILES_V="2.4.1"
FUNCTIONPLOT_V="1.26.0"
JSXGRAPH_V="1.14.3"
MATTER_V="0.20.0"
TIKZJAX_V="1.0.0-beta24"    # npm @drgrice1/tikzjax: TeX (WebAssembly) + pgf/TikZ, pgfplots 1.18.1, tikz-cd
# circuitikz 1.0 and chemfig 1.4 TeX files, as used by obsidian-tikzjax (pinned commit of artisticat1/tikzjax)
EXTRA_TEX_COMMIT="ba892f23a2d280d018681a4f88b39f5a8648c7c7"

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$HERE/.work"
OUT="$HERE/out"
STAGE="$WORK/stage"
rm -rf "$WORK" && mkdir -p "$WORK/npm" "$STAGE" "$OUT"

npm_fetch() { # <package> <version> → $WORK/npm/<safe name>/package
  local pkg="$1" ver="$2" safe="${1//\//_}"
  local url
  url="$(curl -fsSL "https://registry.npmjs.org/${pkg}/${ver}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["dist"]["tarball"])')"
  mkdir -p "$WORK/npm/$safe"
  curl -fsSL -o "$WORK/npm/$safe.tgz" "$url"
  tar xzf "$WORK/npm/$safe.tgz" -C "$WORK/npm/$safe"
  echo "$WORK/npm/$safe/package"
}

echo "• mermaid $MERMAID_V"
P="$(npm_fetch mermaid "$MERMAID_V")"
mkdir -p "$STAGE/mermaid" && cp "$P/dist/mermaid.min.js" "$STAGE/mermaid/"
printf 'Mermaid %s, MIT License, (c) Knut Sveidqvist and contributors. https://github.com/mermaid-js/mermaid\n' "$MERMAID_V" > "$STAGE/mermaid/LICENSE.txt"

echo "• smiles-drawer $SMILES_V"
P="$(npm_fetch smiles-drawer "$SMILES_V")"
mkdir -p "$STAGE/smiles" && cp "$P/dist/smiles-drawer.min.js" "$STAGE/smiles/" && cp "$P/LICENSE.md" "$STAGE/smiles/LICENSE.md"

echo "• function-plot $FUNCTIONPLOT_V"
P="$(npm_fetch function-plot "$FUNCTIONPLOT_V")"
mkdir -p "$STAGE/functionplot" && cp "$P/dist/function-plot.js" "$P/LICENSE" "$STAGE/functionplot/"

echo "• jsxgraph $JSXGRAPH_V"
P="$(npm_fetch jsxgraph "$JSXGRAPH_V")"
mkdir -p "$STAGE/jsxgraph"
cp "$P/distrib/jsxgraphcore.js" "$P/distrib/jsxgraph.css" "$P/distrib/LICENSE.MIT" "$P/distrib/LICENSE.LGPL" "$STAGE/jsxgraph/"

echo "• matter-js $MATTER_V"
P="$(npm_fetch matter-js "$MATTER_V")"
mkdir -p "$STAGE/matter" && cp "$P/build/matter.min.js" "$STAGE/matter/" && cp "$P/LICENSE" "$STAGE/matter/LICENSE"

echo "• tikzjax $TIKZJAX_V (+ circuitikz, chemfig)"
P="$(npm_fetch @drgrice1/tikzjax "$TIKZJAX_V")"
mkdir -p "$STAGE/tikzjax"
# no source maps, no bakoma TTFs (fonts.css only uses fonts/*.woff2)
cp "$P/dist/tikzjax.js" "$P/dist/run-tex.js" "$P/dist/fonts.css" "$P/dist/core.dump.gz" "$P/dist/tex.wasm.gz" "$STAGE/tikzjax/"
cp -R "$P/dist/fonts" "$P/dist/tex_files" "$STAGE/tikzjax/"
cp "$P/README.md" "$STAGE/tikzjax/README.md"
printf 'TikZJax by Jim Fowler and Glenn Rice, GPL-3.0+.\nSource: https://github.com/drgrice1/tikzjax (npm @drgrice1/tikzjax@%s)\n' "$TIKZJAX_V" > "$STAGE/tikzjax/LICENSE.txt"
EXTRA_TEX=(
  circuitikz.sty t-circuitikz.tex pgfcirc.defines.tex pgfcircbipoles.tex pgfcirccurrent.tex pgfcircflow.tex
  pgfcirclabel.tex pgfcircmonopoles.tex pgfcircmultipoles.tex pgfcircpath.tex pgfcircquadpoles.tex
  pgfcircshapes.tex pgfcirctripoles.tex pgfcircutils.tex pgfcircvoltage.tex
  chemfig.sty chemfig.tex t-chemfig.tex
  simplekv.sty simplekv.tex   # chemfig reads its options with simplekv
)
for f in "${EXTRA_TEX[@]}"; do
  # jsDelivr serves the same commit's files (raw.githubusercontent.com is very slow for many small files)
  curl -fsSL --retry 3 -o "$STAGE/tikzjax/tex_files/$f.gz" \
    "https://cdn.jsdelivr.net/gh/artisticat1/tikzjax@${EXTRA_TEX_COMMIT}/tex_files/$f.gz" ||
  curl -fsSL --retry 3 -o "$STAGE/tikzjax/tex_files/$f.gz" \
    "https://raw.githubusercontent.com/artisticat1/tikzjax/${EXTRA_TEX_COMMIT}/tex_files/$f.gz"
  gzip -t "$STAGE/tikzjax/tex_files/$f.gz"
done
printf 'circuitikz 1.0, chemfig 1.4 and simplekv 0.1 (LPPL), from https://github.com/artisticat1/tikzjax/tree/%s/tex_files\n' "$EXTRA_TEX_COMMIT" \
  >> "$STAGE/tikzjax/LICENSE.txt"

cat > "$STAGE/manifest.json" <<EOF
{
  "bundle": $BUNDLE_VERSION,
  "tag": "$BUNDLE_TAG",
  "components": {
    "mermaid":      { "name": "Mermaid",        "version": "$MERMAID_V",      "entry": "mermaid/mermaid.min.js" },
    "smiles":       { "name": "SmilesDrawer",   "version": "$SMILES_V",       "entry": "smiles/smiles-drawer.min.js" },
    "functionplot": { "name": "function-plot",  "version": "$FUNCTIONPLOT_V", "entry": "functionplot/function-plot.js" },
    "jsxgraph":     { "name": "JSXGraph",       "version": "$JSXGRAPH_V",     "entry": "jsxgraph/jsxgraphcore.js" },
    "matter":       { "name": "Matter.js",      "version": "$MATTER_V",       "entry": "matter/matter.min.js" },
    "tikz":         { "name": "TikZJax",        "version": "$TIKZJAX_V",      "entry": "tikzjax/tikzjax.js",
                      "extras": "pgfplots 1.18.1, tikz-cd 1.0, circuitikz 1.0, chemfig 1.4" }
  }
}
EOF

# fixed timestamps + sorted entries: the same inputs give the same zip
find "$STAGE" -exec touch -h -t 202601010000 {} +
rm -f "$OUT/$ZIP_NAME"
( cd "$STAGE" && find . -type f | LC_ALL=C sort | sed 's|^\./||' | zip -X -q -9 "$OUT/$ZIP_NAME" -@ )
SHA="$(shasum -a 256 "$OUT/$ZIP_NAME" | awk '{print $1}')"
echo "$SHA  $ZIP_NAME" > "$OUT/$ZIP_NAME.sha256"
SIZE="$(wc -c < "$OUT/$ZIP_NAME" | tr -d ' ')"

echo
echo "Bundle:  $OUT/$ZIP_NAME  ($SIZE bytes)"
echo "SHA-256: $SHA"
echo "Upload it to: https://github.com/Santoshkurmi/antigem/releases/tag/$BUNDLE_TAG (asset name $ZIP_NAME)"
