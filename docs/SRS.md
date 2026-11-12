# Especificación de requisitos de software

**Producto:** Dibujo al aire (stb-hand-draw)
**Versión del documento:** 1.0
**Fecha:** 2026-11-12
**Estado:** vigente para la medición de FPS y el dibujo con el mando
**Estilo:** IEEE Std 830-1998, resumido

## Historial

| Versión | Fecha | Descripción |
| --- | --- | --- |
| 1.0 | 2026-11-12 | Requisitos de la app de dibujo con Hand Landmarker en el decodificador de 32 bits |

## 1. Introducción

### 1.1 Propósito

Este documento fija el comportamiento observable de «Dibujo al aire» para quien la construye y quien la prueba en el decodificador. El código del repositorio implementa esta versión.

### 1.2 Alcance

La aplicación sigue una mano con MediaPipe y, mientras se mantiene una tecla del mando, dibuja un trazo con la yema del índice sobre la vista de la cámara.

Incluye la app Android, los dos modelos (lite y full), la medición de FPS y la construcción del APK de 32 bits.

No incluye tienda, cuentas, red, grabación ni reconocimiento de gestos distinto del dibujo con el botón pulsado.

### 1.3 Definiciones

| Término | Significado |
| --- | --- |
| ABI | Arquitectura nativa del APK. Aquí solo `armeabi-v7a`. |
| Análisis | Bitmap que entra al Hand Landmarker. Puede ser 640×480 o 320×240. |
| Diestro / Zurdo | Preferencia de la mano de la persona que puede dibujar. |
| FPS | Resultados de inferencia por segundo. |
| Hand Landmarker | Grafo de MediaPipe de 21 puntos por mano. El punto 8 es la yema del índice. |
| Letterbox | Bandas negras al encajar una imagen 4:3 en la pantalla sin recortar. |
| Lite / full | Dos paquetes `.task`. El full es el oficial. El lite se arma con los gráficos lite oficiales. |

### 1.4 Referencias

1. IEEE Std 830-1998, Recommended Practice for Software Requirements Specifications.
2. MediaPipe Hand Landmarker. Modelo `hand_landmarker` float16, revisión 1.
3. Artefacto Maven `com.google.mediapipe:tasks-vision:1.2.3` y `tasks-core:1.2.3`.
4. Guía de Android TV: `LEANBACK_LAUNCHER`, banner y mando direccional.
5. Restricciones del Claro ZTE B866V2H, las mismas que en stb-body-masks.

### 1.5 Visión general

La sección 2 describe el producto y el equipo. La sección 3 lista interfaces, requisitos funcionales, requisitos no funcionales y criterios de aceptación.

## 2. Descripción general

### 2.1 Perspectiva

La app es autónoma. Lee la cámara con CameraX, estima la mano en el equipo y dibuja en una capa encima de la vista previa. No hay servidor. El manifiesto quita el permiso de internet que arrastra MediaPipe.

### 2.2 Funciones

- Mostrar la cámara en horizontal, en espejo por defecto, con letterbox.
- Estimar una o dos manos y dibujar solo con la mano elegida.
- Medir FPS y milisegundos de inferencia, en pantalla y en logcat.
- Cambiar mano, color, grosor, modelo, delegado, resolución y espejo con el mando.
- Explicar si falta el permiso o la cámara.

### 2.3 Usuarios

Una persona delante de la webcam, con un mando de televisor. Quien mide el rendimiento lee `HandDrawPerf` por adb.

### 2.4 Restricciones del dispositivo

| Aspecto | Restricción |
| --- | --- |
| Equipo | Claro ZTE B866V2H, Android TV 14, API 34 |
| SoC | Amlogic S905X4, Mali-G31, 2 GB de RAM |
| Userspace | Solo 32 bits. ABI del APK: únicamente `armeabi-v7a` |
| Cámara | Webcam USB UVC, Camera2 id `"0"`, `LENS_FACING` FRONT |
| Entrada | Mando: D-pad, OK, atrás. Números y colores son opcionales |
| Pantalla | Televisor horizontal, sin táctil obligatorio |

### 2.5 Suposiciones

- MediaPipe Tasks 1.2.3 es el runtime de 32 bits: `tasks-vision` no trae `.so`, y `tasks-core` 1.2.3 incluye `jni/armeabi-v7a/libmediapipe_tasks_jni.so` (ELF 32 bits ARM) con `HandLandmarkerGraph`. Se comprobó el contenido de las AAR. La 0.10.26 es solo arm64.
- El modelo full (`hand_landmarker.task`, SHA-256 `fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1`) se descarga al compilar.
- El modelo lite (SHA-256 `d40f91342b5bcd27300529ba26476b248bf6ff3570fb28ff9e10bd8256b5dc43`) está en el repositorio. No existe un lite oficial; se construye con `palm_detection_lite.tflite` y `hand_landmark_lite.tflite`, que tienen las mismas formas de tensor que el full, más los metadatos de normalización del paquete full.
- El bitmap de análisis no está espejado. MediaPipe asume un selfie, así que la etiqueta de mano se invierte. La vista previa sí se espeja con `scaleX`, y los trazos usan la misma proyección letterbox que la app de máscaras.
- Hay como máximo un fotograma de inferencia en vuelo.
- La GPU puede no estar disponible. El valor por defecto es CPU.

## 3. Requisitos específicos

### 3.1 Interfaces

**Pantalla.** Completa, horizontal, en español. FPS en grande y, debajo, los milisegundos de la última inferencia. Una línea indica mano elegida, modelo, delegado, resolución y número de manos. Otra recuerda el mando. El panel de ajustes cabe en un lateral y se recorre con el D-pad.

**Mando.** Ver la tabla del README. Resumen exigible: OK mantenido dibuja y al soltar termina el trazo; las repeticiones de tecla no abren otro trazo; ← cambia la mano y, si se mantiene, abre los ajustes; → cambia el color y, si se mantiene, el grosor; ↑ deshace; ↓ borra; Atrás corto pregunta si hay trazos y sale si no hay; Atrás largo sale. Modelo, delegado, resolución, número de manos y espejo se cambian desde los ajustes (D-pad) y también con teclas numéricas o de color si el mando las tiene. La preferencia de mano se guarda.

**Cámara.** Permiso `CAMERA` en tiempo de ejecución. Vista previa CameraX en modo compatible, FIT_CENTER, objetivo 640×480, 4:3. Análisis YUV con el mismo aspecto y el tamaño elegido (640×480 o 320×240). Si el fotograma llega más grande, se reduce sin deformar antes de la inferencia. Estrategia de quedarse con el fotograma más reciente, y no convertir un fotograma nuevo mientras hay una inferencia en vuelo.

**Registro.** Cada segundo, tag `HandDrawPerf`, una línea `fps=` `inferMs=` `frames=` `model=` `delegate=` `target=` `frame=` `hands=` `tracked=`.

**Sistema.** `minSdk` 26, `targetSdk` 34. Banner de Android TV. Táctil, cámara y leanback no son requisitos de instalación. `largeHeap` en falso. Sin `INTERNET`. APK de depuración firmado con el keystore del repositorio (contraseña pública `android`), sin marca `testOnly`.

### 3.2 Requisitos funcionales

| ID | Requisito |
| --- | --- |
| RF-01 | La actividad responde a `MAIN` + `LAUNCHER` y a `MAIN` + `LEANBACK_LAUNCHER`, con banner e icono, en horizontal y en español. |
| RF-02 | Sin permiso de cámara, se explica el uso y se puede pedir. Si se niega, la app no se cierra por una excepción y ofrece reintentar o abrir los ajustes. |
| RF-03 | Si no hay cámara o CameraX no puede enlazarla, hay un aviso y se puede reintentar. Si existe cámara frontal, se usa esa. |
| RF-04 | El detector es Hand Landmarker en `LIVE_STREAM`. El número de manos es 1 o 2 (por defecto 1). El modelo es lite o full (por defecto lite). El delegado es CPU o GPU (por defecto CPU). |
| RF-05 | Si crear el grafo en GPU falla, o el primer error de ejecución es de GPU, se reintenta en CPU y se avisa. Si el lite no carga, se reintenta el full en CPU. |
| RF-06 | Solo la mano elegida dibuja. La etiqueta usa la handedness de MediaPipe corregida porque el fotograma de análisis no está espejado. Si hay dos manos de la preferida, gana la de mayor puntuación. |
| RF-07 | Con OK (o Enter) pulsado, la yema del índice añade puntos suavizados al trazo. Soltar cierra el trazo. Una repetición de tecla no abre un segundo trazo. |
| RF-08 | El punto del índice de la mano elegida se ve aunque no se esté dibujando. La otra mano, si se ve, no dibuja. |
| RF-09 | Los trazos se guardan en coordenadas del fotograma y se dibujan en pantalla con letterbox y el mismo espejo que la vista previa. |
| RF-10 | ↑ deshace el trazo en curso o, si no hay, el último cerrado. ↓ borra todos. Atrás corto pide confirmación si hay tinta. |
| RF-11 | Se puede cambiar color, grosor, modelo, manos, delegado, resolución de análisis y espejo. Mano, color, grosor, modelo, delegado, manos, resolución y espejo se recuerdan. |
| RF-12 | Los FPS y los milisegundos se muestran en pantalla. Cada segundo se escribe la línea `HandDrawPerf`. |
| RF-13 | El APK contiene únicamente `lib/armeabi-v7a`, con `libmediapipe_tasks_jni.so` de 32 bits, y los dos modelos. |

### 3.3 Requisitos no funcionales

| ID | Requisito |
| --- | --- |
| RNF-01 | Un solo fotograma en vuelo. Si una inferencia no vuelve en 2 s, se admite otro fotograma. |
| RNF-02 | La vista previa no se bloquea esperando al modelo: el análisis se queda con el fotograma más reciente. |
| RNF-03 | La compilación falla si el SHA-256 del modelo full o del lite no coincide. |
| RNF-04 | La CI compila en push, pull request y `workflow_dispatch`, ejecuta las pruebas de unidad, verifica el ABI y sube el APK. En `main` publica el release `latest` con checkout y `gh --repo`. |

### 3.4 Criterios de aceptación

1. `./gradlew :app:testDebugUnitTest :app:assembleDebug` termina sin error.
2. `tools/verify_apk_abi.sh` acepta el APK de depuración y rechazaría otra ABI.
3. En el decodificador, `adb install -r` sustituye la instalación anterior sin `-t`.
4. Con la mano elegida delante de la webcam y OK mantenido, aparece un trazo alineado con el dedo en la vista en espejo. Al soltar, el trazo se queda.
5. `adb logcat -s HandDrawPerf` muestra una línea por segundo con `fps` e `inferMs`.
6. Cambiar a 320×240, al modelo full o a GPU (si arranca) modifica la línea de log, de modo que se pueden comparar.

### 3.5 Riesgos

- En el S905X4 el Hand Landmarker puede quedar por debajo de los 7 FPS de la pose, o mejorar con lite y 320×240. La CI no lo mide.
- El modelo lite depende de que el grafo 1.2.3 acepte esos `.tflite` con los metadatos injertados. Si no, el requisito RF-05 carga el full.
- El espejo automático de CameraX en cámaras frontales, sumado a `scaleX`, puede invertir la imagen respecto de un selfie. El ajuste de espejo mueve la vista y los trazos juntos. La alineación entre ambos es el requisito; el sentido del espejo se corrige con esa tecla.
