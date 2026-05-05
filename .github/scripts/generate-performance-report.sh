#!/usr/bin/env bash
set -euo pipefail

# Generates PERFORMANCE.md from graphus index --benchmark-json runs.
# Env: GRAPHUS_JAR, CHROMA_URL, RELEASE_TAG, RUNNER_OS, CHROMA_IMAGE, REPOS_JSON, OUT_MD

GRAPHUS_JAR="${GRAPHUS_JAR:?GRAPHUS_JAR required}"
CHROMA_URL="${CHROMA_URL:?CHROMA_URL required}"
RELEASE_TAG="${RELEASE_TAG:?RELEASE_TAG required}"
RUNNER_OS="${RUNNER_OS:-unknown}"
CHROMA_IMAGE="${CHROMA_IMAGE:-chromadb/chroma:1.1.0}"
REPOS_JSON="${REPOS_JSON:?REPOS_JSON required}"
OUT_MD="${OUT_MD:?OUT_MD required}"
WORKDIR="${WORKDIR:-${RUNNER_TEMP:-/tmp}/graphus-perf}"

mkdir -p "${WORKDIR}"

clone_repo() {
  local slug="$1"
  local ref="$2"
  local dest="$3"
  rm -rf "${dest}"
  if [[ "${ref}" =~ ^[0-9a-f]{40}$ ]]; then
    mkdir -p "${dest}"
    git -C "${dest}" init -q
    git -C "${dest}" remote add origin "https://github.com/${slug}.git"
    git -C "${dest}" fetch --depth 1 origin "${ref}"
    git -C "${dest}" checkout -q FETCH_HEAD
  else
    git clone --branch "${ref}" --depth 1 "https://github.com/${slug}.git" "${dest}"
  fi
}

UTC_NOW="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
SAFE_TAG="$(echo "${RELEASE_TAG}" | tr -c 'a-zA-Z0-9_' '_')"

{
  echo "# Graphus indexing performance"
  echo ""
  echo "Automated benchmark attached to GitHub Release **${RELEASE_TAG}**."
  echo ""
  echo "## Environment"
  echo ""
  echo "| Field | Value |"
  echo "| --- | --- |"
  echo "| Generated (UTC) | ${UTC_NOW} |"
  echo "| Runner | ${RUNNER_OS} |"
  echo "| Chroma image | ${CHROMA_IMAGE} |"
  echo "| Chroma URL (in job) | ${CHROMA_URL} |"
  echo "| Embedding | local (ONNX MiniLM; cold CI runs may download weights unless cached) |"
  echo ""
  echo "Tiers are named fixture repos at pinned refs (manifest: \`.github/performance-repos.json\`). Compare trends across releases; single CI runs vary."
  echo ""
  echo "## Results"
  echo ""
  echo "| Tier | Repo @ ref | Workspace | Clear | Parse | Index | Checksum | Sum phases | Parsed files | Indexed symbols |"
  echo "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"
} >"${OUT_MD}"

append_workspace_rows() {
  local tier="$1"
  local slug="$2"
  local ref="$3"
  local json="$4"

  jq -r --arg slug "${slug}" --arg ref "${ref}" --arg tier "${tier}" '
    .workspaces[] |
    [
      $tier,
      ($slug + " @ " + $ref),
      .workspaceName,
      (.clearNanos / 1000000000),
      (.parseNanos / 1000000000),
      (.indexNanos / 1000000000),
      (.checksumNanos / 1000000000),
      ((.clearNanos + .parseNanos + .indexNanos + .checksumNanos) / 1000000000),
      .parsedFiles,
      .indexedSymbols
    ] | @tsv
  ' "${json}" | while IFS=$'\t' read -r c0 c1 c2 c3 c4 c5 c6 c7 c8 c9; do
    printf '| %s | %s | %s | %.2fs | %.2fs | %.2fs | %.2fs | %.2fs | %s | %s |\n' \
      "${c0}" "${c1}" "${c2}" "${c3}" "${c4}" "${c5}" "${c6}" "${c7}" "${c8}" "${c9}" >>"${OUT_MD}"
  done
}

while IFS=$'\t' read -r tier slug ref; do
  [[ -z "${tier}" ]] && continue
  dest="${WORKDIR}/clone-${tier}"
  json="${WORKDIR}/bench-${tier}.json"
  clone_repo "${slug}" "${ref}" "${dest}"

  collection="perf_${tier}_${SAFE_TAG}"

  java -jar "${GRAPHUS_JAR}" index \
    --repo "${dest}" \
    --db chroma \
    --db-url "${CHROMA_URL}" \
    --embedding local \
    --collection "${collection}" \
    --state-dir "${WORKDIR}/state-${tier}" \
    --benchmark-json "${json}"

  append_workspace_rows "${tier}" "${slug}" "${ref}" "${json}"

done < <(jq -r '.repos[] | "\(.tier)\t\(.slug)\t\(.ref)"' "${REPOS_JSON}")

{
  echo ""
  echo "**Sum phases** is clear+parse+index+checksum for that workspace row. Global wall clock per tier JSON includes \`totalWallNanos\` (full JVM run)."
  echo ""
  echo "## Raw JSON (per tier)"
  echo ""
} >>"${OUT_MD}"

while IFS=$'\t' read -r tier slug ref; do
  [[ -z "${tier}" ]] && continue
  json="${WORKDIR}/bench-${tier}.json"
  {
    echo "### ${tier}: ${slug} @ ${ref}"
    echo ""
    echo '```json'
    cat "${json}"
    echo '```'
    echo ""
  } >>"${OUT_MD}"
done < <(jq -r '.repos[] | "\(.tier)\t\(.slug)\t\(.ref)"' "${REPOS_JSON}")
