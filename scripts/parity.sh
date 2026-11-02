#!/usr/bin/env bash
# Compares drvPaths of nixpkgs attributes between nix-instantiate and affogato.
# usage: scripts/parity.sh NIXPKGS ATTR...
set -uo pipefail
nixpkgs=$1
shift
# AFFOGATO may be a command line, as "java build/jenesis/Execute.java".
affogato=${AFFOGATO:-$(dirname "$0")/../result/bin/affogato}
same=0 differ=0 both=0 nixonly=0 affogatoonly=0
for attr in "$@"; do
    ref=$(nix-instantiate --readonly-mode -I nixpkgs="$nixpkgs" '<nixpkgs>' -A "$attr" 2>/dev/null | head -1)
    got=$($affogato drv-path -I nixpkgs="$nixpkgs" -E 'import <nixpkgs> {}' -A "$attr" 2>/dev/null | head -1)
    if [[ -z $ref && -z $got ]]; then both=$((both + 1)); echo "both-fail     $attr"
    elif [[ -z $got ]]; then nixonly=$((nixonly + 1)); echo "AFFOGATO-FAIL $attr"
    elif [[ -z $ref ]]; then affogatoonly=$((affogatoonly + 1)); echo "NIX-FAIL      $attr"
    elif [[ $ref == "$got" ]]; then same=$((same + 1)); echo "same          $attr"
    else differ=$((differ + 1)); echo "DIFFER        $attr $ref $got"
    fi
done
echo "identical: $same, different: $differ, failed in both: $both, failed in affogato only: $nixonly, failed in nix only: $affogatoonly"
