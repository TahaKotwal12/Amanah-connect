import { Fill, LegalPage } from '../LegalPage'

export default function TermsPage() {
  return (
    <LegalPage title="Terms of service">
      <p>
        Last updated: <Fill>date</Fill>. These terms apply to your use of Amanah Connect, operated
        by <Fill>legal name and registered address of the operator</Fill>.
      </p>

      <h2>The service</h2>
      <p>
        Amanah Connect helps a community manage its members, dues, donations, budgets, complaints
        and announcements. Members do not need accounts; community administrators do.
      </p>

      <h2>Accounts</h2>
      <p>
        Administrators are responsible for keeping their sign-in details and two-factor codes safe,
        and for what is done with their account.
      </p>

      <h2>Payments are between members and the community</h2>
      <p>
        Amanah Connect shows payment details, such as a UPI QR code, but does not hold, process or
        guarantee member payments. A payment is recorded when a community administrator confirms it.
      </p>

      <h2>Your data</h2>
      <p>
        The community owns the information it puts into the service. We process it as described in
        the privacy policy, and the community can export it at any time.
      </p>

      <h2>Acceptable use</h2>
      <p>
        You agree not to misuse the service, attempt to access other communities’ data, or upload
        unlawful content.
      </p>

      <h2>Plans and fees</h2>
      <p>
        <Fill>
          plan fees, billing period, tax treatment, renewal, refund and cancellation terms
        </Fill>
      </p>

      <h2>Availability and liability</h2>
      <p>
        <Fill>service availability commitments, limitation of liability and indemnities</Fill>
      </p>

      <h2>Governing law</h2>
      <p>
        <Fill>governing law and place of dispute resolution</Fill>
      </p>

      <h2>Contact</h2>
      <p>
        <Fill>contact email for questions about these terms</Fill>
      </p>
    </LegalPage>
  )
}
