import 'package:flutter_test/flutter_test.dart';

import 'package:movil/main.dart';

void main() {
  testWidgets('muestra la pantalla minima "hola"', (WidgetTester tester) async {
    await tester.pumpWidget(const ModelCollabMovilApp());
    expect(find.text('hola'), findsOneWidget);
  });
}
