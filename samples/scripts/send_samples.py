#!/usr/bin/env python3
"""Send the 15 synthetic email fixtures to an explicitly configured SMTP test mailbox."""
from __future__ import annotations
import argparse, json, smtplib
from email.message import EmailMessage
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
FIXTURES=json.loads((ROOT/'expected'/'email_manifest.json').read_text(encoding='utf-8'))

def main():
    p=argparse.ArgumentParser(); p.add_argument('--host',required=True); p.add_argument('--port',type=int,default=587); p.add_argument('--username',required=True); p.add_argument('--password',required=True); p.add_argument('--to',required=True); p.add_argument('--starttls',action='store_true'); a=p.parse_args()
    with smtplib.SMTP(a.host,a.port,timeout=30) as smtp:
        if a.starttls: smtp.starttls()
        smtp.login(a.username,a.password)
        for data in FIXTURES:
            msg=EmailMessage(); msg['From']=data['sender']; msg['To']=a.to; msg['Subject']=data['subject']; msg['X-Clinevo-Synthetic']='true'
            msg.set_content(data['body']+'\n\nSYNTHETIC TEST DATA — NO REAL PATIENT INFORMATION.')
            smtp.send_message(msg); print('sent',data['id'])
if __name__=='__main__': main()
