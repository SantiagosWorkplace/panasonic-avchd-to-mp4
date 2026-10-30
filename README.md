# panasonic-avchd-to-mp4

Programa para Windows que detecta una videocámara **Panasonic HC-V130** conectada por USB y, con un clic:

- Convierte los videos **AVCHD 1080/60i** (`.MTS`) a **MP4 1080p a 60 cuadros por segundo** (59,94).
- Copia las **fotos**.
- Ordena todo por fecha en el Escritorio:

```
Escritorio\HANDYCAM\
└── 2026-10-30\
    ├── 2026-10-30_14-35-12_00000.mp4
    ├── 2026-10-30_15-02-41_00001.mp4
    └── Fotos\
        └── 2026-10-30_14-40-00_P1000001.JPG
```

> ✅ **Probado en una Panasonic HC-V130.** Ver [compatibilidad](#compatibilidad-con-otras-cámaras) para otros modelos.

## Por qué

La HC-V130 graba con su mejor calidad en AVCHD (modo **HA**), pero eso deja archivos `.MTS` entrelazados (60i) dentro de una estructura de carpetas difícil de recorrer. Su MP4 nativo, en cambio, llega solo a 1080/30p.

Este programa toma lo mejor de los dos: graba en AVCHD HA y obtiene **MP4 a 60p reales**. Para eso desentrelaza el video: cada campo del 60i pasa a ser un cuadro completo, así que el movimiento se ve tan fluido como en la pantalla de la cámara.

## Funciones

- **Detección automática** de la cámara por su modelo USB (no cualquier tarjeta SD).
- Botón **Iniciar proceso**: no arranca solo.
- Cartel de estado: *Esperando cámara* → *Cámara lista* → *En proceso...* → *Finalizado*.
- **No reprocesa** lo que ya existe en la carpeta de destino: podés conectar la cámara cuantas veces quieras.
- **Rápido**: usa la aceleración por hardware **Intel Quick Sync** si está disponible. En un Core i3-10110U convierte más rápido que la duración del video (unas 26 veces más rápido que por procesador). Si no hay Quick Sync, usa x264 por procesador.
- **No borra nada** de la tarjeta.

## Uso

1. Configurá la cámara en **AVCHD, modo HA**, y poné en hora su reloj (las carpetas usan esa fecha).
2. Abrí `HANDYCAM.exe`.
3. Conectá la cámara por USB y, si te lo pregunta, elegí el modo **PC**.
4. Cuando el cartel diga **Cámara lista**, tocá **Iniciar proceso**.
5. Al ver **Finalizado** podés desconectar la cámara.

## Compilar

Requisitos: Windows, [JDK 21 (Temurin)](https://adoptium.net/) y [FFmpeg](https://www.gyan.dev/ffmpeg/builds/).

```powershell
winget install EclipseAdoptium.Temurin.21.JDK Gyan.FFmpeg
.\build.ps1
```

El resultado queda en `dist\HANDYCAM\`: `HANDYCAM.exe` con su propio Java y `ffmpeg.exe` incluidos, así que no hace falta instalar nada en la PC donde se use. Hay que copiar la carpeta completa.

## Detalles técnicos

| | |
|---|---|
| Entrada | `PRIVATE\AVCHD\BDMV\STREAM\*.MTS` y `DCIM\*\*.JPG` |
| Desentrelazado | `bwdif` en modo `send_field` (60i → 60p) |
| Video | H.264 High, 1920×1080, 59,94 fps (Quick Sync `global_quality 18` o x264 `crf 18`) |
| Audio | AAC 192 kbps |
| Fecha | fecha de modificación del archivo en la tarjeta (reloj de la cámara) |

## Compatibilidad con otras cámaras

**Probado:** Panasonic **HC-V130**.

El programa busca el texto `HC-V130` en el modelo USB de la cámara (la HC-V130 se presenta como `MATSHITA HC-V130/SD`). Para usarlo con otro modelo hay que cambiar `CAMERA_MODEL` en [`src/HandycamConverter.java`](src/HandycamConverter.java) y recompilar.

| Tipo de cámara | ¿Funciona la conversión? |
|---|---|
| Panasonic de gama de entrada de la misma generación, que graban AVCHD **solo en 1080/60i** en tarjeta SD (por ejemplo, HC-V100 y HC-V110) | Debería ser totalmente compatible (**no probado**) |
| Modelos PAL que graban **1080/50i** | Sí: el resultado sale a 50p |
| Panasonic o Sony que además tienen un modo AVCHD **1080/60p** | Sí en los modos 60i. Los clips en 60p **no**: el programa los trataría como entrelazados y duplicaría los cuadros |
| Sony Handycam con AVCHD 60i | La conversión sí (usa la misma estructura de carpetas); hay que cambiar la detección |
| Cámaras que graban solo MP4 / XAVC / 4K, de cinta (MiniDV/HDV) o que se conectan por MTP | No |

## Licencia

Todavía no tiene licencia. Si querés usar el código, abrí un issue.

---

🤖 Generado con [Claude Code](https://claude.com/claude-code)
