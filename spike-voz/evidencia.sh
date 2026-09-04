#!/usr/bin/env bash
# Spike B-2 — recolecta la evidencia del spike en un solo comando.
# Ejecutar DESPUES de dictar la frase, con la pantalla del resultado a la vista
# y el modo avion todavia activado.
#
#   bash spike-voz/evidencia.sh
#
# Deja:
#   evidencias/spike-voz-modo-avion.png   <- la captura para la defensa
#   evidencias/spike-voz-entorno.txt      <- datos del dispositivo para el veredicto

set -uo pipefail

ADB="${ADB:-C:/Users/Daniel/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SALIDA="$RAIZ/evidencias"
mkdir -p "$SALIDA"

if ! "$ADB" devices | grep -qE '[[:space:]]device$'; then
  echo "ERROR: no hay dispositivo autorizado. Revisa spike-voz/CHECKLIST_DANIEL.md" >&2
  "$ADB" devices >&2
  exit 1
fi

prop() { "$ADB" shell getprop "$1" 2>/dev/null | tr -d '\r'; }

# El modo avion se lee de Settings.Global; 1 = activado.
AVION="$("$ADB" shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r')"

{
  echo "# Entorno del spike B-2 — $(date '+%Y-%m-%d %H:%M:%S')"
  echo
  echo "serial            : $("$ADB" get-serialno | tr -d '\r')"
  echo "fabricante        : $(prop ro.product.manufacturer)"
  echo "modelo            : $(prop ro.product.model)"
  echo "Android           : $(prop ro.build.version.release) (API $(prop ro.build.version.sdk))"
  echo "build             : $(prop ro.build.display.id)"
  echo "modo avion        : ${AVION:-desconocido} (1 = activado)"
  echo
  echo "## Reconocedores de voz instalados"
  "$ADB" shell pm list packages 2>/dev/null | tr -d '\r' \
    | grep -iE 'speech|voice|googlequicksearchbox|tts' || echo "(ninguno detectado)"
  echo
  echo "## Version del proveedor de reconocimiento (Google)"
  "$ADB" shell dumpsys package com.google.android.googlequicksearchbox 2>/dev/null \
    | tr -d '\r' | grep -m2 -E 'versionName|versionCode' || echo "(no instalado)"
} > "$SALIDA/spike-voz-entorno.txt"

# La captura decisiva SOLO se escribe si el modo avion esta realmente activado.
# Con red, la corrida es de contraste y se guarda con otro nombre: nunca se
# puede confundir una con otra al armar la defensa.
if [ "${AVION:-0}" = "1" ]; then
  DESTINO="$SALIDA/spike-voz-modo-avion.png"
else
  DESTINO="$SALIDA/spike-voz-baseline-con-red.png"
fi
"$ADB" exec-out screencap -p > "$DESTINO"

echo "--- entorno ---"
cat "$SALIDA/spike-voz-entorno.txt"
echo
echo "captura: $DESTINO ($(wc -c < "$DESTINO") bytes)"

if [ "${AVION:-0}" != "1" ]; then
  echo
  echo "AVISO: modo avion NO activado -> se guardo como BASELINE, no como evidencia." >&2
fi
