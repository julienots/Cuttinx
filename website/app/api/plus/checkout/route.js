import { NextResponse } from 'next/server'
import { origin, stripe, stripeReady } from '@/lib/plus'

export const dynamic = 'force-dynamic'

/** /api/plus/checkout?plan=monthly|yearly : opens Stripe's payment page for CUTTINX Plus. */
export async function GET(req) {
  const site = origin(req)
  if (!stripeReady()) return NextResponse.redirect(`${site}/plus?indisponible=1`, 303)
  const plan = new URL(req.url).searchParams.get('plan') === 'yearly' && process.env.STRIPE_PRICE_YEARLY ? 'yearly' : 'monthly'
  const price = plan === 'yearly' ? process.env.STRIPE_PRICE_YEARLY : process.env.STRIPE_PRICE_MONTHLY
  try {
    const session = await stripe('checkout/sessions', {
      method: 'POST',
      params: {
        mode: 'subscription',
        'line_items[0][price]': price,
        'line_items[0][quantity]': '1',
        success_url: `${site}/plus/merci?session_id={CHECKOUT_SESSION_ID}`,
        cancel_url: `${site}/plus`,
        allow_promotion_codes: 'true',
        locale: 'fr',
      },
    })
    return NextResponse.redirect(session.url, 303)
  } catch (e) {
    console.error('Plus checkout', e)
    return NextResponse.redirect(`${site}/plus?erreur=1`, 303)
  }
}
