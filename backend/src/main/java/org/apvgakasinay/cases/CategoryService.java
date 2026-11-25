package org.apvgakasinay.cases;

import java.util.List;
import org.apvgakasinay.cases.internal.CaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Categories are data. This is the read side; managing them comes in a later milestone. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CategoryService {

    private final CaseRepository repository;

    public CategoryService(CaseRepository repository) {
        this.repository = repository;
    }

    /** Every active category, plus inactive ones still used by a public case, in display order. */
    public List<CategoryResponse> listed() {
        return repository.listedCategories();
    }
}
