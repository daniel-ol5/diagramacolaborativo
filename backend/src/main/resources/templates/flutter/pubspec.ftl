# Generado por ModelCollab a partir del diagrama "${nombreDelDiagrama}".
name: ${paquete}
description: Asistente de voz de ${nombreDelDiagrama}, generado desde el diagrama UML.
publish_to: 'none'
version: 1.0.0

environment:
  sdk: ^3.8.0

dependencies:
  flutter:
    sdk: flutter
  # REST contra el backend Spring Boot generado del mismo diagrama.
  http: ^1.2.0
  # RF-08.4: persistencia local para operar sin conexion.
  sqflite: ^2.3.0
  path: ^1.9.0
  # STT: reconocimiento de voz del sistema, con el modelo de idioma ya instalado.
  speech_to_text: ^7.0.0
  # TTS: la respuesta hablada.
  flutter_tts: ^4.2.0

dev_dependencies:
  flutter_lints: ^5.0.0

flutter:
  uses-material-design: true
  assets:
    # El catalogo de intenciones, derivado del metamodelo. Viaja como dato y no
    # como codigo: es lo que permite cambiar el diagrama y regenerar solo esto.
    - assets/intents.json
