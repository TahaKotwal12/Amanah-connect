import { Fill, LegalPage } from '../LegalPage'

export default function PrivacyPolicyPage() {
  return (
    <LegalPage title="Privacy policy">
      <p>
        Last updated: <Fill>date</Fill>. This policy explains what information Amanah Connect
        handles and why. Amanah Connect is operated by{' '}
        <Fill>legal name and registered address of the operator</Fill>.
      </p>

      <h2>Two roles</h2>
      <p>
        When a community uses Amanah Connect to manage its members, the community decides what
        member information is kept and why. We process that information on the community’s behalf.
        For visitors to this website and for the administrators who sign in, we decide how
        information is used.
      </p>

      <h2>What we collect</h2>
      <ul>
        <li>
          From visitors who ask for a demo: name, email address, optional phone number, community
          name, approximate size and message.
        </li>
        <li>
          From community administrators: name, email address, sign-in details (passwords are kept
          only as one-way hashes) and a record of their actions in the service.
        </li>
        <li>
          On behalf of communities: member names, contact details, groups, invoices, payments,
          receipts, complaints, announcements and uploaded files.
        </li>
      </ul>

      <h2>How we use it</h2>
      <ul>
        <li>To reply to demo requests and run the service.</li>
        <li>
          To send the emails a community asks us to send, such as invoices, receipts and
          announcements, only to members who have agreed to receive them.
        </li>
        <li>To keep the service secure, and to investigate problems using an audit log.</li>
      </ul>

      <h2>Who else handles it</h2>
      <p>
        We use service providers to host the service, store files and send email.{' '}
        <Fill>list of providers and the country each stores data in</Fill>
      </p>

      <h2>How long we keep it</h2>
      <p>
        <Fill>
          retention periods for demo requests, community data after a subscription ends, and audit
          logs
        </Fill>
      </p>

      <h2>Your choices</h2>
      <p>
        A community can export its data and ask for a member’s personal details to be erased. If you
        are a member, contact your community; if you are a visitor or administrator, contact us at{' '}
        <Fill>privacy contact email</Fill>.
      </p>

      <h2>Cookies and analytics</h2>
      <p>
        The site uses a sign-in cookie for administrators.{' '}
        <Fill>statement on analytics, if any are added</Fill>
      </p>

      <h2>Changes</h2>
      <p>
        If we change this policy we will update the date above.{' '}
        <Fill>how users are told of material changes</Fill>
      </p>
    </LegalPage>
  )
}
