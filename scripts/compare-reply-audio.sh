#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
    printf 'uso: bash scripts/compare-reply-audio.sh original.ogg convertido.ogg\n' >&2
    exit 2
fi
input="$1"
output="$2"
[ -f "$input" ] || { printf 'original não encontrado\n' >&2; exit 1; }
[ ! -e "$output" ] || { printf 'saída já existe; escolha outro nome\n' >&2; exit 1; }
command -v ffmpeg >/dev/null
command -v ffprobe >/dev/null
printf '\nSHA-256 original\n'
sha256sum -- "$input"
printf '\nMetadados originais\n'
ffprobe -v error -show_streams -show_format -of json "$input"
printf '\nDecodificação completa do original\n'
if ffmpeg -nostdin -v error -xerror -i "$input" -map 0:a:0 -f null -; then
    printf 'Original decodificou sem erro. Isso não prova compatibilidade com WhatsApp.\n'
else
    printf 'Original falhou na decodificação; preservado para análise. Tentando conversão.\n' >&2
fi
printf '\nConversão equivalente ao servidor externo\n'
ffmpeg -nostdin -v error -n -i "$input" -c:a libopus -b:a 32k -ar 16000 -ac 1 -f ogg "$output"
ffmpeg -nostdin -v error -xerror -i "$output" -map 0:a:0 -f null -
printf '\nMetadados convertidos\n'
ffprobe -v error -show_streams -show_format -of json "$output"
printf '\nSHA-256 convertido\n'
sha256sum -- "$output"
printf '\nImporte o convertido pelo botão diagnóstico do áudio. Nenhuma mensagem foi enviada por este script.\n'
