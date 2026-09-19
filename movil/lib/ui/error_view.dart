import 'package:flutter/material.dart';

/// Mismo bloque de error+reintentar en cualquier pantalla que liste algo
/// contra la red -- proyectos, diagramas, lo que siga.
class ErrorView extends StatelessWidget {
  const ErrorView({super.key, required this.mensaje, required this.onReintentar});

  final String mensaje;
  final VoidCallback onReintentar;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(mensaje, textAlign: TextAlign.center),
            const SizedBox(height: 12),
            FilledButton(onPressed: onReintentar, child: const Text('Reintentar')),
          ],
        ),
      ),
    );
  }
}
