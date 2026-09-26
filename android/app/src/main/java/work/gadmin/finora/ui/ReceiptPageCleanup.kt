package work.gadmin.finora.ui

/** Remove MEV's floating promotion/cookie launcher, never fiscal document content. */
internal val mevReceiptCleanup =
    """(() => {
    if (location.protocol !== 'https:' || location.hostname !== 'mev.sfs.md') return false;
    const clean = () => {
        document.querySelectorAll('button.mud-cookie-reopen').forEach(node => node.remove());
        document.querySelectorAll('div.fixed.p-4').forEach(node => {
            const promotion = node.hasAttribute('data-v-7ba5bd90') ||
                node.querySelector('a[href="https://moldovaeuropeana.md/"]');
            if (promotion) node.remove();
        });
    };
    if (!document.getElementById('finora-mev-receipt-style')) {
        const style = document.createElement('style');
        style.id = 'finora-mev-receipt-style';
        style.textContent = 'div.fixed.p-4[data-v-7ba5bd90], button.mud-cookie-reopen { display: none !important; }';
        (document.head || document.documentElement).appendChild(style);
    }
    clean();
    if (!window.__finoraMevReceiptObserver) {
        const observer = new MutationObserver(clean);
        observer.observe(document.documentElement, { childList: true, subtree: true });
        window.__finoraMevReceiptObserver = observer;
        window.addEventListener('pagehide', () => {
            observer.disconnect();
            delete window.__finoraMevReceiptObserver;
        }, { once: true });
    }
    return true;
})()"""
