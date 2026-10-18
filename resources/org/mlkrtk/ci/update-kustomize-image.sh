#!/usr/bin/env bash
#
# Sets the tag of one image in kustomization file(s), leaving everything else untouched.
#
# Usage: update-kustomize-image.sh <path> <image> <tag> [images-entry-name]
#
#   path               kustomization.yaml, or a directory searched recursively for kustomization.y(a)ml
#   image              image without tag, e.g. 123456789012.dkr.ecr.ap-south-1.amazonaws.com/tetris
#   tag                new tag
#   images-entry-name  `name:` of the images entry to update. Default: the entry whose `name:` equals the
#                      image or its last path segment, or whose `newName:` equals the image.
#
# Fails if a file has no matching images entry, so a wrong path or name breaks the build instead of
# silently deploying nothing.
set -euo pipefail

if [ "$#" -lt 3 ]; then
    echo "Usage: $0 <path> <image> <tag> [images-entry-name]" >&2
    exit 64
fi

manifest_path=$1
image=$2
tag=$3
entry_name=${4:-}

if ! [[ "${tag}" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]]; then
    echo "ERROR: '${tag}' is not a valid image tag" >&2
    exit 1
fi

files=()
if [ -f "${manifest_path}" ]; then
    files=("${manifest_path}")
elif [ -d "${manifest_path}" ]; then
    while IFS= read -r f; do
        files+=("${f}")
    done < <(find "${manifest_path}" -type f \( -name kustomization.yaml -o -name kustomization.yml \) | sort)
fi
if [ "${#files[@]}" -eq 0 ]; then
    echo "ERROR: no kustomization file found at '${manifest_path}'" >&2
    exit 1
fi

# Buffers each item of the top-level `images:` list and rewrites (or adds) newTag only in matching items.
AWK_PROGRAM=$(cat <<'AWK'
function value_of(line) {
    sub(/^[^:]*:[ \t]*/, "", line)
    sub(/[ \t]+#.*$/, "", line)
    sub(/[ \t]+$/, "", line)
    if (line ~ /^".*"$/ || line ~ /^'.*'$/) line = substr(line, 2, length(line) - 2)
    return line
}
function is_match() {
    if (want != "") return name == want
    return name == image || name == short || newname == image
}
function flush(    i, prefix) {
    if (n == 0) return
    if (is_match()) {
        matched++
        if (tag_line) {
            prefix = buf[tag_line]
            sub(/newTag:.*/, "", prefix)
            buf[tag_line] = prefix "newTag: \"" tag "\""
        }
        for (i = 1; i <= n; i++) {
            print buf[i]
            if (!tag_line && i == last) print indent "newTag: \"" tag "\""
        }
    } else {
        for (i = 1; i <= n; i++) print buf[i]
    }
    n = 0; last = 0; tag_line = 0; name = ""; newname = ""
}
in_images && /^[^ \t#-]/ { flush(); in_images = 0 }    # next top-level key ends the list
!in_images {
    print
    if ($0 ~ /^images:[ \t]*(#.*)?$/) in_images = 1
    next
}
/^[ \t]*-([ \t]|$)/ {                                   # start of a list item
    flush()
    match($0, /^[ \t]*-[ \t]*/)
    indent = substr($0, 1, RLENGTH)
    gsub(/-/, " ", indent)
}
n == 0 && !/^[ \t]*-([ \t]|$)/ { print; next }          # blank/comment lines before the first item
{
    buf[++n] = $0
    if ($0 !~ /^[ \t]*(#.*)?$/) last = n
    if ($0 ~ /^[ \t-]*name:/) name = value_of($0)
    else if ($0 ~ /^[ \t-]*newName:/) newname = value_of($0)
    else if ($0 ~ /^[ \t-]*newTag:/) tag_line = n
}
END { flush(); exit (matched ? 0 : 3) }
AWK
)

for file in "${files[@]}"; do
    tmp=$(mktemp)
    if ! awk -v image="${image}" -v short="${image##*/}" -v tag="${tag}" -v want="${entry_name}" \
            "${AWK_PROGRAM}" "${file}" > "${tmp}"; then
        rm -f "${tmp}"
        echo "ERROR: ${file}: no images entry matches '${entry_name:-${image}}'. Entries found:" >&2
        grep -E '^[[:space:]-]*(name|newName):' "${file}" | sed 's/^/    /' >&2 || true
        echo "Set kustomizeImageName to the 'name:' of the entry to update." >&2
        exit 1
    fi
    if cmp -s "${tmp}" "${file}"; then
        echo "${file}: already at ${tag}"
    else
        cat "${tmp}" > "${file}"    # preserves file mode
        echo "${file}: newTag -> ${tag}"
        git --no-pager diff -- "${file}" 2>/dev/null || true
    fi
    rm -f "${tmp}"
done
