#!/usr/bin/env python3
"""Arma models/hand_landmarker_lite.task.

Google no publica hand_landmarker_lite.task. Este paquete usa los gráficos
oficiales, que tienen las mismas formas de tensor que el full de la revisión 1:

- https://storage.googleapis.com/mediapipe-assets/palm_detection_lite.tflite
  SHA-256 e9a4aaddf90dda56a87235303cf00e4c2d3fb28725f68fd88772997dac905c18
- https://storage.googleapis.com/mediapipe-assets/hand_landmark_lite.tflite
  SHA-256 d7fde8ac11f8ce03f8663775bfc323f4fc9f2a38062b4f4efa142874ef5b2a48

El grafo de tasks-core 1.2.3 lee la normalización (media 0, desviación 255)
desde TFLITE_METADATA. Esos metadatos, y el zip asociado (labels.txt /
handedness.txt), se copian de los .tflite que hay dentro de:

- https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task
  SHA-256 fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1

Los dos .tflite resultantes se guardan sin compresión como hand_detector.tflite
y hand_landmarks_detector.tflite. El archivo del repositorio debe tener SHA-256

  7eade15cb07478ae0077533d1a191be5023e32ff04338e2c574e8023f902626f

La compilación de Gradle comprueba ese resumen. Para regenerar hace falta
flatc y los módulos schema_generated / metadata_schema_generated en PYTHONPATH;
el binario ya está en el repositorio para que la CI no dependa de eso.
"""

import hashlib
import pathlib
import sys

EXPECTED = "7eade15cb07478ae0077533d1a191be5023e32ff04338e2c574e8023f902626f"
ROOT = pathlib.Path(__file__).resolve().parents[1]
TARGET = ROOT / "models" / "hand_landmarker_lite.task"


def main() -> int:
    if not TARGET.is_file():
        print(f"No está {TARGET}", file=sys.stderr)
        return 1
    digest = hashlib.sha256(TARGET.read_bytes()).hexdigest()
    if digest != EXPECTED:
        print(f"SHA-256 inesperado: {digest}", file=sys.stderr)
        return 1
    print(f"{digest}  {TARGET}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
