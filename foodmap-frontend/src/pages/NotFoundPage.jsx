import { Link } from 'react-router-dom';
import { buttonClass } from '../components/ui/Button';

export default function NotFoundPage() {
  return (
    <section className="mx-auto flex max-w-md flex-col items-center px-4 py-20 text-center sm:py-28">
      <p aria-hidden="true" className="font-display text-[5rem] font-black leading-none text-brand-600">404</p>
      <h1 className="mt-4 font-display text-h2 text-ink">Không tìm thấy trang này</h1>
      <p className="mt-2 text-body text-ink-muted">
        Đường dẫn có thể đã sai hoặc trang không còn tồn tại.
      </p>
      <div className="mt-8 flex w-full flex-col gap-3 sm:w-auto sm:flex-row">
        <Link to="/" className={buttonClass()}>Về trang chủ</Link>
        <Link to="/map" className={buttonClass({ variant: 'secondary' })}>Xem bản đồ</Link>
      </div>
    </section>
  );
}
