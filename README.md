# Dibujo al aire

Dibuja trazos en el aire con la yema del índice, delante de una webcam, pensado para el decodificador **Claro ZTE B866V2H** (Android TV 14, Amlogic S905X4, 2 GB de RAM, userspace solo de 32 bits). También se puede instalar en un teléfono para probar.

La mano sale de MediaPipe Hand Landmarker. La imagen se procesa en el dispositivo. La app no pide permiso de internet. La especificación está en [`docs/SRS.md`](docs/SRS.md).

En el decodificador, la app de pose midió unos 7 FPS en CPU. Esta app vuelve a medir con el Hand Landmarker: los FPS y los milisegundos de inferencia se ven en pantalla y se escriben en logcat.

## ABI

El APK lleva **solo `armeabi-v7a`**. En este decodificador no existe `lib64`. Se usa MediaPipe Tasks Vision **1.2.3** porque `tasks-core` de esa versión incluye `libmediapipe_tasks_jni.so` en 32 bits, y ese `.so` contiene el grafo `HandLandmarkerGraph`. La 0.10.26 de `tasks-vision` es solo arm64 y no sirve. La integración continua rechaza el APK si aparece otra ABI o si falta esa biblioteca.

Google no publica un `hand_landmarker_lite.task`. El modelo **lite** de esta app empaqueta los gráficos oficiales `palm_detection_lite.tflite` y `hand_landmark_lite.tflite` (mismos tensores de entrada y salida que el full) y les copia los metadatos del paquete full, que el grafo de la 1.2.3 necesita para normalizar la imagen. El **full** es el `hand_landmarker.task` float16 revisión 1, descargado en la compilación y comprobado por SHA-256. El paquete lite está alineado a 4 bytes, que es lo que MediaPipe necesita para leer el `.task`. El valor por defecto es lite, GPU, 1 mano y análisis 320×240. Si el lite o la GPU no arrancan se prueba otra combinación, pero esa sustitución no se guarda: en logcat `requested` es lo pedido y `model` es lo que quedó cargado.

## Descargar e instalar

Cuando el código está en `main`, el APK de depuración queda en el release `latest`:

[https://github.com/CravApp/stb-hand-draw/releases/download/latest/app-debug.apk](https://github.com/CravApp/stb-hand-draw/releases/download/latest/app-debug.apk)

En un pull request, el mismo APK está en los artefactos de la workflow «Compilar APK» (`app-debug-armeabi-v7a`).

En el ordenador, con la depuración del decodificador activada:

```bash
adb connect <ip-del-decodificador>:5555
adb install -r app-debug.apk
```

Si el equipo está enchufado por USB, basta el `adb install`. La firma de depuración es estable (keystore del repositorio, contraseña pública `android`), así que `-r` sustituye la versión anterior. Esa clave no sirve para publicar en una tienda.

La primera vez el mando debe aceptar el permiso de cámara. Sin cámara, o si se niega el permiso, la app lo explica y no se cierra.

## Medir FPS

Cada segundo, con la app en primer plano:

```bash
adb logcat -s HandDrawPerf
```

Una línea típica:

```text
fps=15 inferMs=60 convertMs=4 drawMs=1 frames=15 model=lite requested=lite delegate=GPU target=320x240 frame=320x240 hands=1 tracked=1
```

`fps` son resultados de inferencia en esa ventana. `inferMs` es la media de la inferencia, `convertMs` la de copiar el fotograma RGBA al bitmap reutilizado y `drawMs` la del último dibujo. Si en ese segundo no hubo resultados, esos tiempos van a 0. `model` es el que quedó cargado; `requested` es el que pediste. Al arrancar, y si un modelo falla, hay otra línea `load requested=... loaded=... error=...` con el mismo tag. `frame` es el bitmap que entró al modelo. La vista previa sigue en 640×480. En pantalla, los FPS y los milisegundos de inferencia van grandes, arriba a la izquierda.

## Mando

La interfaz está en horizontal y en español. No hace falta pantalla táctil. Cada acción tiene una tecla del D-pad; los números y los colores son atajos para mandos que los traen.

| Tecla | Acción |
| --- | --- |
| OK, Centro o Enter (mantener) | Dibujar con la yema del índice (punto 8) |
| OK (soltar) | Terminar el trazo |
| ← | Alternar Diestro / Zurdo |
| ← (mantener) | Abrir ajustes y ayuda |
| → | Siguiente color |
| → (mantener) | Siguiente grosor |
| ↑ | Deshacer el último trazo |
| ↓ | Borrar todos los trazos |
| Atrás (pulsación corta) | Preguntar antes de borrar; si no hay trazos, salir |
| Atrás (mantener) | Salir |
| Menú, Info o 0 | Abrir ajustes (Atrás los cierra) |
| En ajustes: ↑↓ | Elegir opción |
| En ajustes: ←→ u OK | Cambiar la opción |
| 1 / 2 | Diestro / Zurdo |
| 3 o amarillo | Grosor |
| 4 o azul | Modelo lite / full |
| 5 o verde | CPU / GPU |
| 6 o rojo | Análisis 640×480 / 320×240 |
| 7 o reproducir | 1 o 2 manos |
| 8 | Espejo de la vista previa |
| 9 | Color |
| Borrar o rebobinar | Deshacer |
| Stop | Borrar todo |

Solo dibuja la mano elegida (Diestro o Zurdo). Si se ven las dos, se usa esa. La preferencia, el color, el grosor, el modelo, el delegado, la resolución y el espejo se guardan. El punto del índice se ve siempre que esa mano está en cuadro; la otra mano, si aparece, se marca en gris y no dibuja.

Mientras hay un aviso de permiso o la pregunta de borrado, las flechas mueven el foco y no dibujan. En la pregunta, OK sobre «Borrar» confirma y Atrás cancela.

El espejo empieza activado, igual que en la app de máscaras: la vista previa usa `scaleX = -1` y los trazos se proyectan con el mismo volteo, con letterbox 4:3. La webcam del decodificador es Camera2 id `"0"`, `LENS_FACING` FRONT.

## Compilar

Hace falta JDK 21, Android SDK 34 y Build-Tools 34.0.0. La primera compilación descarga el modelo full (se comprueba el SHA-256) y copia el lite del repositorio, también comprobado.

```bash
export ANDROID_HOME="$HOME/android-sdk"
./gradlew :app:testDebugUnitTest :app:assembleDebug
bash tools/verify_apk_abi.sh app/build/outputs/apk/debug/app-debug.apk
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Integración continua

`.github/workflows/build-apk.yml` corre en cada push, cada pull request y con `workflow_dispatch`. Compila, ejecuta las pruebas de unidad, comprueba el ABI y sube el APK. En `main` actualiza el release `latest`. El job de release hace checkout y llama a `gh` con `--repo`, que es lo que hacía falta para publicar el release.

Ese release necesita que el token de Actions pueda escribir contenidos. Si el job falla con un 403, en el repositorio: **Settings → Actions → General → Workflow permissions → Read and write permissions**.

## Limitaciones

- La CI no tiene el decodificador y no mide FPS. El número sale de `HandDrawPerf` en el equipo.
- El delegado GPU del Mali-G31 puede fallar al crear el grafo o en el primer fotograma. Si falla, la app pasa a CPU y lo dice en pantalla.
- Con `manos = 1`, MediaPipe puede seguir la mano que no está elegida. Sube a 2 si pasa.
- El modelo lite no es un archivo oficial de Google. Si no arranca, la app carga el full con el mismo delegado y lo escribe en `HandDrawPerf` (`load ... error=`). La tecla 4 vuelve a pedir lite.
- La vista previa sigue en 640×480. El análisis puede ser 320×240. Si los dos recortes de la cámara no coinciden, el trazo puede desplazarse un poco. La proyección es letterbox y respeta el espejo.
- MediaPipe etiqueta la mano como si la imagen fuera un selfie. El bitmap de análisis no está espejado, así que la app invierte la etiqueta. Si el HAL ya entregara la imagen en espejo, Diestro y Zurdo quedarían cruzados; el ajuste sigue eligiendo una mano de forma estable.
- El APK es de depuración, no un paquete de tienda.
- Hay un fotograma de inferencia en vuelo, para no llenar los 2 GB.

## Terceros

- [MediaPipe Tasks](https://github.com/google-ai-edge/mediapipe) (Apache 2.0), versión 1.2.3.
- Modelo `hand_landmarker` float16 revisión 1, de Google.
- `palm_detection_lite.tflite` y `hand_landmark_lite.tflite` de `storage.googleapis.com/mediapipe-assets`, empaquetados en `models/hand_landmarker_lite.task`.
- CameraX 1.3.4 y el resto de AndroidX, con sus licencias respectivas.
