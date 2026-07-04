import 'package:pocketbase/pocketbase.dart';

Future<void> main(List<String> args) async {
  if (args.isEmpty) {
    throw StateError('Usage: dart run smoke.dart <baseUrl>');
  }

  final pb = PocketBase(args.first);
  await pb.send('/api/bootstrap/superuser', method: 'POST', body: {
    'email': 'root@example.com',
    'password': 'secret123',
  });
  await pb.collection('_superusers').authWithPassword('root@example.com', 'secret123');

  final collectionName = 'dart_smoke_posts';
  await pb.send('/api/collections', method: 'POST', body: {
    'name': collectionName,
    'type': 'base',
    'listRule': '',
    'viewRule': '',
    'createRule': '',
    'updateRule': '',
    'deleteRule': '',
    'fields': [
      {'name': 'title', 'type': 'text', 'required': true},
      {'name': 'count', 'type': 'number'},
    ],
  });

  final created = await pb.collection(collectionName).create(body: {
    'title': 'from dart',
    'count': 1,
  });
  final updated = await pb.collection(collectionName).update(created.id, body: {
    'title': 'from dart updated',
    'count': 2,
  });
  if (updated.data['title'] != 'from dart updated') {
    throw StateError('record update did not roundtrip through Dart SDK');
  }

  final list = await pb.collection(collectionName).getList(page: 1, perPage: 10);
  if (list.items.isEmpty) {
    throw StateError('Dart SDK list returned no records');
  }

  await pb.collection(collectionName).delete(created.id);
  print('Dart SDK Smoke Test Passed!');
}
