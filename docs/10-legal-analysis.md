# 10 — Legal Analysis: Right to Interoperability

## Overview

This document analyses the legal framework supporting the right to reverse engineer medical device communication protocols for interoperability, with focus on EU regulations and the #WeAreNotWaiting movement.

## EHDS — European Health Data Space

The **European Health Data Space Regulation** (EU 2025/327) establishes:

### Article 3: Right to Access Electronic Health Data

> Natural persons shall have the right to access their electronic health data processed in the context of healthcare [...] in an easily readable, consolidated and accessible form.

### Article 7: Right to Data Portability

> Natural persons shall have the right to transmit electronic health data [...] to a recipient of their choice.

### Relevance

Insulin pump data (basal rates, bolus history, glucose data) constitutes electronic health data under EHDS. The regulation grants patients the right to access and port this data — which requires understanding the communication protocol.

## GDPR — General Data Protection Regulation

### Article 15: Right of Access

Patients have the right to obtain from Ypsomed/CamDiab all personal data being processed, including:
- Pump settings and delivery history
- CGM data transmitted via the app
- Algorithm decisions and dosing recommendations
- Cloud-stored health data

### Article 20: Right to Data Portability

> The data subject shall have the right to receive the personal data concerning him or her [...] in a structured, commonly used and machine-readable format and have the right to transmit those data to another controller.

This directly supports the development of alternative apps that can communicate with the pump.

## EU Software Directive (2009/24/EC)

### Article 6: Decompilation

Decompilation is permitted without authorisation when:

1. **Performed by a licensee** — the APK was obtained as a licensed user
2. **Necessary for interoperability** — no documentation is available for the BLE protocol
3. **Limited to parts necessary** — only communication protocol was analysed
4. **Not used to create competing product** — AAPS is open-source, non-commercial

> "The authorisation of the rightholder shall not be required where reproduction of the code and translation of its form [...] are indispensable to obtain the information necessary to achieve the interoperability of an independently created computer program with other programs."

## EU Cyber Resilience Act (CRA)

The CRA (Regulation 2024/2847) establishes cybersecurity requirements for products with digital elements. Notably:

### Article 13(6): Security Researchers

> Manufacturers shall facilitate the analysis and testing of the software included in the product with digital elements by security researchers.

### Vulnerability Disclosure

The CRA mandates coordinated vulnerability disclosure processes. Our security findings (Section 08) align with this framework.

## Right to Repair (EU)

The **EU Right to Repair Directive** (2024/1799) establishes:

- Consumers' right to have products repaired
- Access to spare parts, tools, and repair information
- Manufacturers cannot use software locks to prevent repair

While primarily targeting consumer electronics, the principle extends to software interoperability of medical devices that patients depend on daily.

## DMCA / US Copyright Law

### Section 1201(f): Reverse Engineering for Interoperability

> A person who has lawfully obtained the right to use a copy of a computer program may circumvent a technological measure [...] for the sole purpose of identifying and analysing those elements of the program that are necessary to achieve interoperability of an independently created computer program.

This applies directly to developing an AndroidAPS driver for the YpsoPump.

### Section 1201(j): Security Testing

> It is not a violation [...] to engage in an act of security testing, if such act does not constitute infringement and is performed solely to test, investigate, correct, or improve the security of the computer system.

Our security findings (CVEs, missing cert pinning, hardcoded secrets) qualify as security research.

## Medical Device Regulation (EU MDR 2017/745)

### Patient Safety Argument

The #WeAreNotWaiting movement argues:

1. **Commercial solutions are insufficient** — CamAPS FX is the only app for YpsoPump, creating single-vendor dependency
2. **Algorithmic choice** — patients should choose their preferred algorithm (oref1/OpenAPS may suit some patients better)
3. **Availability** — CamAPS FX is not available in all countries
4. **Customisation** — individual patients have unique needs that commercial solutions may not address
5. **Data sovereignty** — patients should control their own health data flow

### Precedent: OpenAPS / Loop / AAPS

- **OpenAPS** has been used safely by thousands of patients since 2015
- **AndroidAPS** is a widely used open-source AID system
- **Loop** (iOS) has comparable safety records
- Multiple peer-reviewed studies confirm safety of DIY closed-loop systems
- The FDA has not pursued enforcement against DIY systems

## German Law (StGB, UrhG)

### § 69e UrhG — Decompilation

German copyright law explicitly permits decompilation for interoperability (implementing EU Directive 2009/24/EC). The conditions mirror the EU directive.

### § 202a StGB — Ausspähen von Daten

"Hacking" law — does NOT apply when:
- The device is owned by the researcher
- The data being accessed is the researcher's own health data
- The purpose is interoperability, not unauthorised access

### § 303a StGB — Datenveränderung

Data modification law — writing commands to one's own pump using an alternative app does not constitute criminal data modification, as the patient has the right to control their own device.

## Disclaimer

This analysis is provided for informational purposes only and does not constitute legal advice. The legal landscape around medical device interoperability is evolving. Key points:

1. **Personal use**: Reverse engineering for personal interoperability is well-protected under EU and US law
2. **No distribution of copyrighted code**: This repository does not include decompiled source code
3. **Security research**: Vulnerability findings follow responsible disclosure principles
4. **Patient autonomy**: The right to choose one's own medical device software is increasingly recognised
5. **#WeAreNotWaiting**: A global movement of patients and developers creating open-source diabetes technology

## References

- [EU EHDS Regulation 2025/327](https://eur-lex.europa.eu/eli/reg/2025/327)
- [EU Software Directive 2009/24/EC](https://eur-lex.europa.eu/eli/dir/2009/24)
- [EU Cyber Resilience Act 2024/2847](https://eur-lex.europa.eu/eli/reg/2024/2847)
- [EU Right to Repair Directive 2024/1799](https://eur-lex.europa.eu/eli/dir/2024/1799)
- [GDPR Articles 15, 20](https://gdpr-info.eu/)
- [US DMCA §1201](https://www.law.cornell.edu/uscode/text/17/1201)
- [EU MDR 2017/745](https://eur-lex.europa.eu/eli/reg/2017/745)
- [OpenAPS.org](https://openaps.org/)
- [AndroidAPS Documentation](https://androidaps.readthedocs.io/)
- [#WeAreNotWaiting](https://www.wearenotwaiting.org/)
