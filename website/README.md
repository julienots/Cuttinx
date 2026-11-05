# Site CUTTINX PRO

Vitrine de l'appli + téléchargements Android (APK) et Windows (EXE / MSI), avec une page privée pour publier
chaque nouvelle version. Next.js 15 + Vercel Blob (stockage des fichiers).

## Mettre en ligne sur Vercel

### Option 1 : depuis GitHub (rien à installer)
1. Sur vercel.com : **Add New… → Project**, choisis le dépôt **Cuttinx**.
2. **Root Directory** : `website`. Framework : Next.js (détecté). Clique **Deploy**.
   Le site est sur la branche de travail : dans *Settings → Git*, mets cette branche comme branche de production,
   ou fusionne-la dans `main`.

### Option 2 : depuis le zip (avec Node.js installé)
1. Décompresse le zip, ouvre un terminal dans le dossier.
2. `npx vercel` (connexion à ton compte), puis `npx vercel --prod`.

### Puis, une seule fois : le stockage des fichiers
1. Dans le projet Vercel : **Storage → Create → Blob**, accès **Public**.
2. **Connect** au projet (tous les environnements). Cela ajoute la variable `BLOB_READ_WRITE_TOKEN`.
3. **Deployments → … → Redeploy** pour que le site la prenne en compte.

## Publier une version
Ouvre ta page privée `https://<ton-site>/admin/<ton-code>` (le code t'a été donné séparément ; il n'est pas dans ce dossier,
seule son empreinte SHA-256 est dans `lib/auth.js`).

1. Télécharge les fichiers de la dernière compilation (GitHub → Actions → dernier build vert → *Artifacts*) :
   `cuttinx-apk.zip` (contient l'APK) et `cuttinx-pro-windows.zip` (contient l'EXE et le MSI). Décompresse-les.
2. Sur la page privée : numéro de version, nouveautés, dépose l'APK, l'EXE (et le MSI si tu veux), **Publier**.
3. Le site affiche la nouvelle version en moins d'une minute ; les anciens fichiers sont supprimés.

Liens à partager, toujours vers la dernière version : `/telecharger/android` et `/telecharger/windows`.

### Changer le code de la page privée
Dans Vercel : *Settings → Environment Variables*, ajoute `ADMIN_TOKEN` = ton nouveau code (long et aléatoire), puis redéploie.
L'ancien code ne marche plus. Une mauvaise adresse affiche simplement « page introuvable ».

## Bon à savoir
- L'offre gratuite de Vercel a des quotas de stockage et de bande passante : avec beaucoup de téléchargements,
  surveille l'onglet *Usage* (ou passe à l'offre Pro).
- En local : `npm install` puis `npm run dev` (sans `BLOB_READ_WRITE_TOKEN`, les boutons affichent « Bientôt disponible »).

## Publicité (Google AdSense + AdMob)
- Le site charge les **annonces automatiques AdSense** du compte `ca-pub-2847478731079395` (le même compte Google qu'AdMob).
  Autre compte : variable Vercel `NEXT_PUBLIC_ADSENSE_CLIENT=ca-pub-…` ; `off` pour retirer les pubs. Redéployer ensuite.
- `public/ads.txt` (AdSense) et `public/app-ads.txt` (AdMob, appli Android) déclarent l'éditeur autorisé à vendre les pubs.
- Dans AdSense : **Sites → Ajouter un site**, saisis ton domaine (un vrai nom de domaine, pas `*.vercel.app`), puis
  demande l'examen. Les pubs apparaissent toutes seules une fois le site validé.
- Dans la fiche Google Play de l'appli, indique ce même domaine comme site du développeur : AdMob y lit `app-ads.txt`.

## CUTTINX Plus (abonnement)
Page publique : `/plus`. Paiement par **Stripe** (carte, Apple Pay, Google Pay), sans Google Play.
Après paiement, la page `/plus/merci` donne le **code d'activation** (bouton « Ouvrir dans CUTTINX » sur Android,
code à coller sur PC). Le code est signé par le site et vérifié hors ligne par l'appli ; l'appli le renouvelle
toute seule via `/api/plus/refresh` tant que l'abonnement Stripe est payé.

Variables à ajouter dans Vercel (*Settings → Environment Variables*), puis redéployer :
| Variable | Valeur |
|---|---|
| `PLUS_SIGNING_KEY` | La clé privée de signature (fichier `plus-signing-key.pem` fourni à part, **jamais dans le dépôt**). Sans elle, aucun code ne peut être créé. |
| `STRIPE_SECRET_KEY` | Clé secrète Stripe (`sk_live_…`, ou `sk_test_…` pour essayer) |
| `STRIPE_PRICE_MONTHLY` | ID du prix mensuel Stripe (`price_…`) |
| `STRIPE_PRICE_YEARLY` | (facultatif) ID du prix annuel |
| `PLUS_PRICE_MONTHLY_LABEL` / `PLUS_PRICE_YEARLY_LABEL` | (facultatif) prix affichés, ex. `4,99 € / mois` |
| `NEXT_PUBLIC_SITE_URL` | (conseillé) adresse du site, ex. `https://cuttinx.xyz` |

Dans Stripe : *Catalogue de produits → Ajouter un produit* « CUTTINX Plus », prix **récurrent** mensuel (et annuel),
puis copie les `price_…`. Sans Stripe configuré, la page affiche « Bientôt disponible ».

**Codes offerts** : la page privée `/admin/<code>` a une section « Offrir CUTTINX Plus » (durée en jours). Pratique
pour tester tout de suite, sans Stripe.
