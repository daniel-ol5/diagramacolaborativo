import 'package:flutter/material.dart';

import 'services/config_service.dart';
import 'ui/home_screen.dart';
import 'ui/login_screen.dart';

void main() {
  runApp(const ModelCollabMovilApp());
}

class ModelCollabMovilApp extends StatelessWidget {
  const ModelCollabMovilApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ModelCollab Móvil',
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo)),
      home: const _SplashRouter(),
    );
  }
}

/// Si ya hay un token guardado, entra directo -- no vuelve a pedir login
/// cada vez que se abre la app.
class _SplashRouter extends StatefulWidget {
  const _SplashRouter();

  @override
  State<_SplashRouter> createState() => _SplashRouterState();
}

class _SplashRouterState extends State<_SplashRouter> {
  @override
  void initState() {
    super.initState();
    ConfigService.sesionGuardada().then((sesion) {
      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(
          builder: (_) => sesion == null ? const LoginScreen() : HomeScreen(sesion: sesion),
        ),
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    return const Scaffold(body: Center(child: CircularProgressIndicator()));
  }
}
