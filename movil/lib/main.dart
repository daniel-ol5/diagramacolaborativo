import 'package:flutter/material.dart';

void main() {
  runApp(const ModelCollabMovilApp());
}

/// Smoke test del camino de instalacion (19/09): esta pantalla no hace
/// nada todavia -- solo confirma que un APK de este proyecto se puede
/// construir, instalar y arrancar en un telefono fisico, algo que ningun
/// Flutter de ModelCollab habia probado antes de hoy.
class ModelCollabMovilApp extends StatelessWidget {
  const ModelCollabMovilApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ModelCollab Móvil',
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo)),
      home: const Scaffold(
        body: Center(
          child: Text('hola', style: TextStyle(fontSize: 32)),
        ),
      ),
    );
  }
}
